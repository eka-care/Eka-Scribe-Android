package com.eka.voice2rx_sdk.audio.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.eka.voice2rx_sdk.audio.ModelDownloader
import com.eka.voice2rx_sdk.common.AudioQualityMetrics
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer

class SquimAnalyzer(private val context: Context) {

    //    private var module: Module? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val sessionLock = Any()


    companion object {
        private const val TAG = "SquimAnalyzer"
    }

    init {
        loadModel()
    }

    /**
     * Load SQUIM model from local storage (download from CDN if not available)
     */
    private fun loadModel() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ortEnvironment = OrtEnvironment.getEnvironment()
                val modelDownloader = ModelDownloader(context)
                VoiceLogger.d(TAG, "Loading SQUIM model...")
                val startTime = System.currentTimeMillis()

                // Download model if needed (uses ETag for caching)
                val modelFile = modelDownloader.downloadModelIfNeeded()
                val sessionOptions = OrtSession.SessionOptions()
                sessionOptions.addCPU(true)
                sessionOptions.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)

                val modelBytes = modelFile.readBytes()
                ortSession = ortEnvironment?.createSession(modelBytes, sessionOptions)

                val loadTime = System.currentTimeMillis() - startTime
                VoiceLogger.d(TAG, "Model loaded successfully in ${loadTime}ms")

                ortSession?.let { session ->
                    VoiceLogger.d(TAG, "Input names: ${session.inputNames}")
                    VoiceLogger.d(TAG, "Output names: ${session.outputNames}")
                }
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error loading model", e)
            }
        }
    }

    /**
     * Analyze audio quality from ShortArray
     *
     * @param audioData ShortArray of audio samples (mono, 16kHz)
     * @return AudioQualityMetrics with STOI, PESQ, SI-SDR scores
     */
    fun analyze(audioData: ShortArray): AudioQualityMetrics {
        // Convert ShortArray to FloatArray and normalize
        val floatData = shortArrayToNormalizedFloat(audioData)

        return analyzeFloat(floatData)
    }

    fun isReady(): Boolean {
        return ortSession != null
    }

    /**
     * Analyze audio quality from FloatArray
     * Handles audio of any length by breaking into 1-second chunks and averaging results
     *
     * @param audioData FloatArray of normalized audio samples (mono, 16kHz, range: -1 to 1)
     * @return AudioQualityMetrics with averaged STOI, PESQ, SI-SDR scores
     */
    fun analyzeFloat(audioData: FloatArray): AudioQualityMetrics {
        if (!isReady()) {
            throw IllegalStateException("Model not loaded")
        }

        try {
            val sampleRate = 16000
            val chunkSize = sampleRate // 1 second chunks
            val totalSamples = audioData.size
            val durationSeconds = totalSamples / sampleRate.toFloat()

            VoiceLogger.d(
                TAG,
                "Analyzing audio: $totalSamples samples (${durationSeconds}s)"
            )

            // If audio is shorter than 1 second, pad it
            if (totalSamples < chunkSize) {
                VoiceLogger.d(TAG, "Audio shorter than 1s, padding to $chunkSize samples")
                val paddedAudio = audioData.copyOf(chunkSize) // Pads with zeros
                return analyzeChunk(paddedAudio)
            }

            // Process audio in 1-second chunks
            val chunks = mutableListOf<FloatArray>()
            var offset = 0

            while (offset < totalSamples) {
                val remainingSamples = totalSamples - offset
                val currentChunkSize = minOf(chunkSize, remainingSamples)

                val chunk = if (currentChunkSize == chunkSize) {
                    // Full chunk - direct copy
                    audioData.copyOfRange(offset, offset + chunkSize)
                } else {
                    // Last chunk is incomplete - pad with zeros
                    VoiceLogger.d(
                        TAG,
                        "Last chunk has $currentChunkSize samples, padding to $chunkSize"
                    )
                    FloatArray(chunkSize).apply {
                        audioData.copyInto(
                            destination = this,
                            destinationOffset = 0,
                            startIndex = offset,
                            endIndex = offset + currentChunkSize
                        )
                    }
                }

                chunks.add(chunk)
                offset += chunkSize
            }

            VoiceLogger.d(TAG, "Processing ${chunks.size} chunk(s)")

            // Analyze each chunk and collect metrics
            val allMetrics = chunks.mapIndexed { index, chunk ->
                VoiceLogger.d(TAG, "Processing chunk ${index + 1}/${chunks.size}")
                analyzeChunk(chunk)
            }

            // Calculate averaged metrics
            val avgStoi = allMetrics.map { it.stoi }.average().toFloat()
            val avgPesq = allMetrics.map { it.pesq }.average().toFloat()
            val avgSiSDR = allMetrics.map { it.siSDR }.average().toFloat()

            VoiceLogger.d(
                TAG,
                "Average Results - STOI: $avgStoi, PESQ: $avgPesq, SI-SDR: $avgSiSDR"
            )

            return AudioQualityMetrics(avgStoi, avgPesq, avgSiSDR)

        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error during analysis", e)
            throw RuntimeException("Analysis failed: ${e.message}", e)
        }
    }

    private fun analyzeChunk(chunk: FloatArray): AudioQualityMetrics {
        val session = ortSession ?: throw IllegalStateException("Model not loaded")
        require(chunk.size == 16000) {
            "Chunk must be exactly 16000 samples, got ${chunk.size}"
        }

        synchronized(sessionLock) {
            val inputShape = longArrayOf(1, chunk.size.toLong())
            val inputTensor = OnnxTensor.createTensor(
                ortEnvironment,
                FloatBuffer.wrap(chunk),
                inputShape
            )

            val inputs = mapOf(session.inputNames.first() to inputTensor)

            val outputs = session.run(inputs)

            val results = mutableMapOf<String, Float>()
            outputs.forEach { (key, value) ->
                val tensor = value as? OnnxTensor
                tensor?.let {
                    val floatBuffer = it.floatBuffer
                    val floatArray = FloatArray(floatBuffer.remaining())
                    floatBuffer.get(floatArray)
                    results[key] = floatArray.getOrElse(0) { 0f }
                }
            }
            VoiceLogger.d(TAG, "Results: $results")

            // Clean up
            inputTensor.close()
            outputs.close()

            // Extract results - handle both Tuple and TensorList
//        {stoi=0.45684606, pesq=1.1719481, si_sdr=-12.458948}
            val stoi = results["stoi"] ?: 0.0f
            val pesq = results["pesq"] ?: 0.0f
            val siSDR = results["si_sdr"] ?: 0.0f

            return AudioQualityMetrics(stoi, pesq, siSDR)
        }
    }

    /**
     * Convert ShortArray to normalized FloatArray
     * Short values are in range [-32768, 32767]
     * Float values will be in range [-1.0, 1.0]
     */
    private fun shortArrayToNormalizedFloat(shortArray: ShortArray): FloatArray {
        return FloatArray(shortArray.size) { i ->
            shortArray[i] / 32768f
        }
    }

    /**
     * Copy asset file to internal storage
     */
    private fun copyAssetToFile(assetName: String): String {
        val file = File(context.filesDir, assetName)

        // Return if already exists
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }

        // Copy from assets
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        return file.absolutePath
    }

    /**
     * Release resources
     */
    fun release() {
        synchronized(sessionLock) {
            try {
                ortSession?.close()
                ortEnvironment?.close()
                VoiceLogger.d(TAG, "Resources released")
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error releasing resources", e)
            } finally {
                ortSession = null
                ortEnvironment = null
            }
        }
    }
}