package com.eka.scribesdk.analyser

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.eka.scribesdk.common.logging.Logger
import java.nio.FloatBuffer

class SquimModelProvider(
    private val modelPath: String,
    private val logger: Logger
) : ModelProvider {

    companion object {
        private const val TAG = "SquimModelProvider"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE = SAMPLE_RATE // 1 second chunks
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val sessionLock = Any()

    override fun load() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                // Disable arena allocation to reduce memory pressure and GC pauses
                addCPU(false)
                setIntraOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // Load from file path directly — avoids readBytes() which doubles memory usage
            ortSession = ortEnvironment?.createSession(modelPath, sessionOptions)
            logger.info(TAG, "SQUIM model loaded from: $modelPath")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load SQUIM model", e)
            throw e
        }
    }

    override fun isLoaded(): Boolean = ortSession != null

    override fun unload() {
        synchronized(sessionLock) {
            ortSession?.close()
            ortSession = null
            ortEnvironment?.close()
            ortEnvironment = null
            logger.info(TAG, "SQUIM model unloaded")
        }
    }

    /**
     * Analyse audio quality. Handles audio of any length by breaking into
     * 1-second (16000 sample) chunks and averaging results.
     */
    fun analyse(audioData: FloatArray): AudioQuality? {
        val session = ortSession ?: return null

        return try {
            val totalSamples = audioData.size

            if (totalSamples < CHUNK_SIZE) {
                val paddedAudio = audioData.copyOf(CHUNK_SIZE)
                return analyseChunk(paddedAudio)
            }

            val results = mutableListOf<AudioQuality>()
            var offset = 0

            while (offset < totalSamples) {
                val remainingSamples = totalSamples - offset
                val chunk = if (remainingSamples >= CHUNK_SIZE) {
                    audioData.copyOfRange(offset, offset + CHUNK_SIZE)
                } else {
                    FloatArray(CHUNK_SIZE).also { padded ->
                        audioData.copyInto(
                            destination = padded,
                            destinationOffset = 0,
                            startIndex = offset,
                            endIndex = offset + remainingSamples
                        )
                    }
                }

                analyseChunk(chunk)?.let { results.add(it) }
                offset += CHUNK_SIZE
            }

            if (results.isEmpty()) return null

            AudioQuality(
                stoi = results.map { it.stoi }.average().toFloat(),
                pesq = results.map { it.pesq }.average().toFloat(),
                siSDR = results.map { it.siSDR }.average().toFloat(),
                overallScore = calculateOverallScore(
                    results.map { it.stoi }.average().toFloat(),
                    results.map { it.pesq }.average().toFloat()
                )
            )
        } catch (e: Exception) {
            logger.error(TAG, "SQUIM analysis failed", e)
            null
        }
    }

    private fun analyseChunk(chunk: FloatArray): AudioQuality? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        synchronized(sessionLock) {
            val inputShape = longArrayOf(1, chunk.size.toLong())
            val inputTensor = OnnxTensor.createTensor(
                env,
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

            inputTensor.close()
            outputs.close()

            val stoi = results["stoi"] ?: 0f
            val pesq = results["pesq"] ?: 0f
            val siSDR = results["si_sdr"] ?: 0f

            return AudioQuality(
                stoi = stoi,
                pesq = pesq,
                siSDR = siSDR,
                overallScore = calculateOverallScore(stoi, pesq)
            )
        }
    }

    private fun calculateOverallScore(stoi: Float, pesq: Float): Float {
        val stoiScore = (stoi.coerceIn(0f, 1f)) * 5f
        val pesqScore = ((pesq + 0.5f) / 5f * 5f).coerceIn(0f, 5f)
        return ((stoiScore + pesqScore) / 2f / 5f).coerceIn(0f, 1f)
    }
}
