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
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    override fun load() {
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()
            ortSession = ortEnvironment?.createSession(modelPath)
            logger.info(TAG, "SQUIM model loaded from: $modelPath")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load SQUIM model", e)
            throw e
        }
    }

    override fun isLoaded(): Boolean = ortSession != null

    override fun unload() {
        ortSession?.close()
        ortSession = null
        ortEnvironment?.close()
        ortEnvironment = null
        logger.info(TAG, "SQUIM model unloaded")
    }

    fun analyse(audioData: FloatArray): AudioQuality? {
        val session = ortSession ?: return null
        val env = ortEnvironment ?: return null

        return try {
            val inputTensor = OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(audioData),
                longArrayOf(1, audioData.size.toLong())
            )

            val results = session.run(mapOf("input" to inputTensor))

            val snr = (results[0].value as Array<*>)[0] as Float
            val clipping =
                if (results.size() > 1) (results[1].value as Array<*>)[0] as Float else 0f
            val loudness =
                if (results.size() > 2) (results[2].value as Array<*>)[0] as Float else 0f

            inputTensor.close()
            results.close()

            AudioQuality(
                snr = snr,
                clipping = clipping,
                loudness = loudness,
                overallScore = calculateOverallScore(snr, clipping, loudness)
            )
        } catch (e: Exception) {
            logger.error(TAG, "SQUIM analysis failed", e)
            null
        }
    }

    private fun calculateOverallScore(snr: Float, clipping: Float, loudness: Float): Float {
        val snrNorm = (snr.coerceIn(0f, 40f)) / 40f
        val clipNorm = 1f - clipping.coerceIn(0f, 1f)
        val loudNorm = (loudness.coerceIn(-40f, 0f) + 40f) / 40f
        return (snrNorm * 0.5f + clipNorm * 0.3f + loudNorm * 0.2f)
    }
}
