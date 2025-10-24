package com.eka.voice2rx_sdk.audio.processing

import android.content.Context
import com.eka.voice2rx_sdk.audio.ModelDownloader
import com.eka.voice2rx_sdk.common.AudioQualityMetrics
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import java.io.FileOutputStream

class SquimAnalyzer(private val context: Context) {

    private var module: Module? = null

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
                val modelDownloader = ModelDownloader(context)
                VoiceLogger.d(TAG, "Loading SQUIM model...")
                val startTime = System.currentTimeMillis()

                // Download model if needed (uses ETag for caching)
                val modelFile = modelDownloader.downloadModelIfNeeded()

                // Load model from local file
                module = Module.load(modelFile.absolutePath)

                val loadTime = System.currentTimeMillis() - startTime
                VoiceLogger.d(TAG, "Model loaded successfully in ${loadTime}ms")

            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error loading model", e)
                throw RuntimeException("Failed to load SQUIM model: ${e.message}", e)
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

    fun isReady(): Boolean = module != null

    /**
     * Analyze audio quality from FloatArray
     *
     * @param audioData FloatArray of normalized audio samples (mono, 16kHz, range: -1 to 1)
     * @return AudioQualityMetrics with STOI, PESQ, SI-SDR scores
     */
    fun analyzeFloat(audioData: FloatArray): AudioQualityMetrics {
        if (!isReady()) {
            throw IllegalStateException("Model not loaded")
        }

        try {
            VoiceLogger.d(
                TAG,
                "Analyzing audio: ${audioData.size} samples (${audioData.size / 16000f}s)"
            )

            // Create input tensor: shape [1, num_samples]
            val inputTensor = Tensor.fromBlob(
                audioData,
                longArrayOf(1, audioData.size.toLong())
            )

            // Run inference
            val output = module!!.forward(IValue.from(inputTensor))

            // Extract results - handle both Tuple and TensorList
            val (stoi, pesq, siSDR) = when {
                output.isTuple -> {
                    // Model returns Tuple
                    val tuple = output.toTuple()
                    Triple(
                        tuple[0].toTensor().dataAsFloatArray[0],
                        tuple[1].toTensor().dataAsFloatArray[0],
                        tuple[2].toTensor().dataAsFloatArray[0]
                    )
                }

                output.isTensorList -> {
                    // Model returns TensorList
                    val tensorList = output.toTensorList()
                    Triple(
                        tensorList[0].dataAsFloatArray[0],
                        tensorList[1].dataAsFloatArray[0],
                        tensorList[2].dataAsFloatArray[0]
                    )
                }

                output.isTensor -> {
                    // Model returns single Tensor with multiple values
                    val tensor = output.toTensor()
                    val data = tensor.dataAsFloatArray
                    Triple(data[0], data[1], data[2])
                }

                else -> {
                    VoiceLogger.e(TAG, "Unexpected output type: ${output.javaClass.simpleName}")
                    throw RuntimeException("Unexpected model output type")
                }
            }

            VoiceLogger.d(TAG, "Results - STOI: $stoi, PESQ: $pesq, SI-SDR: $siSDR")

            return AudioQualityMetrics(stoi, pesq, siSDR)

        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error during analysis", e)
            throw RuntimeException("Analysis failed: ${e.message}", e)
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
        try {
            module?.destroy()
            module = null
            VoiceLogger.d(TAG, "Resources released")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error releasing resources", e)
        }
    }
}