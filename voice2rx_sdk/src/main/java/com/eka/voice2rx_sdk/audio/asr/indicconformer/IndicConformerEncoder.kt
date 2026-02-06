package com.eka.voice2rx_sdk.audio.asr.indicconformer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * IndicConformer Encoder using ONNX Runtime.
 * Runs encoder.onnx to convert mel spectrogram features to acoustic embeddings.
 *
 * Input:
 *   - audio_signal: (B, 80, T) - Log mel spectrogram features
 *   - length: (B,) - Number of frames in T dimension
 *
 * Output:
 *   - outputs: (B, 512, T/4) - Encoded acoustic features (4x time reduction)
 *   - encoded_lengths: (B,) - Actual encoded sequence length
 */
class IndicConformerEncoder(
    private val ortEnvironment: OrtEnvironment,
    modelPath: String
) {
    companion object {
        private const val TAG = "IndicConformerEncoder"
        private const val ENCODER_HIDDEN_DIM = 512
    }

    private val session: OrtSession

    init {
        VoiceLogger.d(TAG, "Loading encoder model from: $modelPath")
        val sessionOptions = OrtSession.SessionOptions().apply {
            // CPU with optimized thread settings
            addCPU(true)
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // Optimize thread count for mobile
            setIntraOpNumThreads(4)
            setInterOpNumThreads(2)

            // Enable graph optimizations
            setMemoryPatternOptimization(true)

            // Try NNAPI for hardware acceleration (GPU/NPU)
            try {
                addNnapi()
                VoiceLogger.d(TAG, "NNAPI execution provider enabled")
            } catch (e: Exception) {
                VoiceLogger.d(TAG, "NNAPI not available, using CPU")
            }
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Encoder model not found: $modelPath")
        }

        val startTime = System.currentTimeMillis()
        session = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
        VoiceLogger.d(
            TAG,
            "Encoder loaded in ${System.currentTimeMillis() - startTime}ms. Inputs: ${session.inputNames}, Outputs: ${session.outputNames}"
        )
    }

    /**
     * Run encoder on mel spectrogram features.
     *
     * @param melFeatures Flat array of mel features, shape (1, 80, T) in row-major order
     * @param numFrames Number of time frames (T dimension)
     * @return Pair of (encoded features flat array, encoded length)
     */
    fun run(melFeatures: FloatArray, numFrames: Int): EncoderOutput {
        VoiceLogger.d(TAG, "Running encoder with $numFrames frames")

        // Create input tensor: shape (1, 80, numFrames)
        val inputTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(melFeatures),
            longArrayOf(1, 80, numFrames.toLong())
        )

        // Create length tensor: shape (1,)
        val lengthTensor = OnnxTensor.createTensor(
            ortEnvironment,
            LongBuffer.wrap(longArrayOf(numFrames.toLong())),
            longArrayOf(1)
        )

        val inputs = mapOf(
            "audio_signal" to inputTensor,
            "length" to lengthTensor
        )

        val startTime = System.currentTimeMillis()
        val results = session.run(inputs)
        val encoderTime = System.currentTimeMillis() - startTime
        VoiceLogger.d(TAG, "Encoder inference took ${encoderTime}ms")

        try {
            // Output "outputs": shape (1, 512, T/4)
            val outputTensor = results[0].value

            // Handle different output formats
            val encoderOutput: FloatArray
            val encodedLength: Int

            when (outputTensor) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    val output3d = outputTensor as Array<Array<FloatArray>>
                    val batchOutput = output3d[0] // Shape: (512, T/4)
                    encodedLength = batchOutput[0].size

                    // Flatten to 1D: shape (512 * T/4)
                    encoderOutput = FloatArray(ENCODER_HIDDEN_DIM * encodedLength)
                    for (d in 0 until ENCODER_HIDDEN_DIM) {
                        for (t in 0 until encodedLength) {
                            encoderOutput[d * encodedLength + t] = batchOutput[d][t]
                        }
                    }
                }

                else -> {
                    // Try to get encoded length from second output
                    encodedLength = try {
                        (results[1].value as LongArray)[0].toInt()
                    } catch (e: Exception) {
                        numFrames / 4 // Default to 4x downsampling
                    }

                    // Get float buffer directly
                    val tensor = results[0] as OnnxTensor
                    val floatBuffer = tensor.floatBuffer
                    encoderOutput = FloatArray(floatBuffer.remaining())
                    floatBuffer.get(encoderOutput)
                }
            }

            VoiceLogger.d(
                TAG,
                "Encoder output: ${encoderOutput.size} values, encoded length: $encodedLength"
            )

            return EncoderOutput(
                features = encoderOutput,
                encodedLength = encodedLength,
                hiddenDim = ENCODER_HIDDEN_DIM
            )
        } finally {
            inputTensor.close()
            lengthTensor.close()
            results.close()
        }
    }

    fun close() {
        try {
            session.close()
            VoiceLogger.d(TAG, "Encoder session closed")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error closing encoder session", e)
        }
    }
}

/**
 * Output from the encoder.
 */
data class EncoderOutput(
    /** Flat array of encoder features, shape (hiddenDim * encodedLength) */
    val features: FloatArray,
    /** Number of encoder time steps (T/4) */
    val encodedLength: Int,
    /** Hidden dimension (512) */
    val hiddenDim: Int
) {
    /**
     * Extract encoder frame at time t.
     * @return FloatArray of size hiddenDim
     */
    fun getFrame(t: Int): FloatArray {
        val frame = FloatArray(hiddenDim)
        for (d in 0 until hiddenDim) {
            frame[d] = features[d * encodedLength + t]
        }
        return frame
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncoderOutput
        return features.contentEquals(other.features) &&
                encodedLength == other.encodedLength &&
                hiddenDim == other.hiddenDim
    }

    override fun hashCode(): Int {
        var result = features.contentHashCode()
        result = 31 * result + encodedLength
        result = 31 * result + hiddenDim
        return result
    }
}
