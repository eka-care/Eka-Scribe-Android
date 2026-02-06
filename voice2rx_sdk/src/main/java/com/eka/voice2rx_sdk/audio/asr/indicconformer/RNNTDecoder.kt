package com.eka.voice2rx_sdk.audio.asr.indicconformer

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import java.io.File
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * RNNT Decoder using ONNX Runtime for IndicConformer ASR.
 * Runs decoder_joint.onnx iteratively using greedy decoding.
 *
 * Input:
 *   - encoder_outputs: (B, 512, T_enc) - Encoder output (or single frame: T_enc=1)
 *   - targets: (B, U) - Previous token ID(s), int32
 *   - target_length: (B,) - Number of target tokens, int32
 *   - input_states_1: (1, B, 640) - LSTM hidden state
 *   - input_states_2: (1, B, 640) - LSTM cell state
 *
 * Output:
 *   - outputs: (B, T_enc, U, 257) - Joint network logits over vocabulary
 *   - prednet_lengths: (B,) - Prediction network output length
 *   - output_states_1: (1, B, 640) - Updated LSTM hidden state
 *   - output_states_2: (1, B, 640) - Updated LSTM cell state
 */
class RNNTDecoder(
    private val ortEnvironment: OrtEnvironment,
    modelPath: String,
    private val vocabulary: List<String>
) {
    companion object {
        private const val TAG = "RNNTDecoder"
        private const val ENCODER_DIM = 512
        private const val LSTM_HIDDEN_DIM = 640
        private const val MAX_SYMBOLS_PER_STEP = 5 // Max tokens to emit per encoder frame
    }

    private val session: OrtSession
    private val blankIdx = vocabulary.size // 256

    init {
        VoiceLogger.d(TAG, "Loading decoder model from: $modelPath")
        val sessionOptions = OrtSession.SessionOptions().apply {
            addCPU(true)
            // Decoder runs sequentially per step, optimize for that
            setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            // Thread optimization for mobile
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)

            // Memory optimization
            setMemoryPatternOptimization(true)
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw IllegalArgumentException("Decoder model not found: $modelPath")
        }

        val startTime = System.currentTimeMillis()
        session = ortEnvironment.createSession(modelFile.absolutePath, sessionOptions)
        VoiceLogger.d(
            TAG,
            "Decoder loaded in ${System.currentTimeMillis() - startTime}ms. Vocab: ${vocabulary.size}, Blank: $blankIdx"
        )
    }

    /**
     * Perform greedy RNNT decoding on encoder output.
     *
     * @param encoderOutput Output from the encoder
     * @return Decoded Hindi text
     */
    fun decode(encoderOutput: EncoderOutput): String {
        val tokens = mutableListOf<Int>()
        var prevToken = blankIdx

        // Initial LSTM states: zeros, shape (1, 1, 640)
        var state1 = FloatArray(LSTM_HIDDEN_DIM)
        var state2 = FloatArray(LSTM_HIDDEN_DIM)

        val startTime = System.currentTimeMillis()
        var totalDecoderCalls = 0

        for (t in 0 until encoderOutput.encodedLength) {
            var symbolsEmitted = 0

            while (symbolsEmitted < MAX_SYMBOLS_PER_STEP) {
                // Extract encoder frame at time t
                val encoderFrame = encoderOutput.getFrame(t)

                // Run decoder-joint network
                val result = runDecoderStep(encoderFrame, prevToken, state1, state2)
                totalDecoderCalls++

                // Update states
                state1 = result.newState1
                state2 = result.newState2

                if (result.predictedToken == blankIdx) {
                    // Blank token - move to next encoder time step
                    break
                } else {
                    // Non-blank token - emit it
                    tokens.add(result.predictedToken)
                    prevToken = result.predictedToken
                    symbolsEmitted++
                }
            }
        }

        val decodeTime = System.currentTimeMillis() - startTime
        VoiceLogger.d(
            TAG,
            "Decode: ${decodeTime}ms, $totalDecoderCalls calls, ${tokens.size} tokens"
        )

        return tokensToText(tokens)
    }

    /**
     * Run a single decoder step.
     */
    private fun runDecoderStep(
        encoderFrame: FloatArray,
        prevToken: Int,
        state1: FloatArray,
        state2: FloatArray
    ): DecoderStepResult {
        // Create input tensors
        // encoder_outputs: shape (1, 512, 1)
        val encTensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(encoderFrame),
            longArrayOf(1, ENCODER_DIM.toLong(), 1)
        )

        // targets: shape (1, 1), int32
        val targetsTensor = OnnxTensor.createTensor(
            ortEnvironment,
            IntBuffer.wrap(intArrayOf(prevToken)),
            longArrayOf(1, 1)
        )

        // target_length: shape (1,), int32
        val targetLenTensor = OnnxTensor.createTensor(
            ortEnvironment,
            IntBuffer.wrap(intArrayOf(1)),
            longArrayOf(1)
        )

        // input_states_1: shape (1, 1, 640)
        val state1Tensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(state1),
            longArrayOf(1, 1, LSTM_HIDDEN_DIM.toLong())
        )

        // input_states_2: shape (1, 1, 640)
        val state2Tensor = OnnxTensor.createTensor(
            ortEnvironment,
            FloatBuffer.wrap(state2),
            longArrayOf(1, 1, LSTM_HIDDEN_DIM.toLong())
        )

        val inputs = mapOf(
            "encoder_outputs" to encTensor,
            "targets" to targetsTensor,
            "target_length" to targetLenTensor,
            "input_states_1" to state1Tensor,
            "input_states_2" to state2Tensor
        )

        val results = session.run(inputs)

        try {
            // Output 0: "outputs" shape (1, 1, 1, 257) - joint logits
            val logits = extractLogits(results[0].value)

            // Argmax over vocabulary dimension
            val predictedToken = logits.indices.maxByOrNull { logits[it] } ?: blankIdx

            // Output 2: "output_states_1" shape (1, 1, 640)
            val newState1 = extractFloatArray(results[2].value)

            // Output 3: "output_states_2" shape (1, 1, 640)
            val newState2 = extractFloatArray(results[3].value)

            return DecoderStepResult(predictedToken, newState1, newState2)
        } finally {
            encTensor.close()
            targetsTensor.close()
            targetLenTensor.close()
            state1Tensor.close()
            state2Tensor.close()
            results.close()
        }
    }

    /**
     * Extract logits from nested array output.
     */
    private fun extractLogits(output: Any): FloatArray {
        return when (output) {
            is Array<*> -> {
                // Could be (1, 1, 1, 257) shape
                var current: Any = output
                while (current is Array<*> && current.isNotEmpty()) {
                    val first = current[0]
                    if (first is FloatArray) {
                        return first
                    }
                    current = first!!
                }
                floatArrayOf()
            }

            is FloatArray -> output
            else -> {
                VoiceLogger.w(TAG, "Unknown logits type: ${output.javaClass}")
                floatArrayOf()
            }
        }
    }

    /**
     * Extract float array from nested output.
     */
    private fun extractFloatArray(output: Any): FloatArray {
        return when (output) {
            is FloatArray -> output
            is Array<*> -> {
                // Could be (1, 1, 640) shape
                var current: Any = output
                while (current is Array<*> && current.isNotEmpty()) {
                    val first = current[0]
                    if (first is FloatArray) {
                        return first
                    }
                    current = first!!
                }
                FloatArray(LSTM_HIDDEN_DIM)
            }

            else -> {
                VoiceLogger.w(TAG, "Unknown state type: ${output.javaClass}")
                FloatArray(LSTM_HIDDEN_DIM)
            }
        }
    }

    /**
     * Convert token IDs to Hindi text using SentencePiece conventions.
     */
    private fun tokensToText(tokenIds: List<Int>): String {
        val sb = StringBuilder()

        for (id in tokenIds) {
            if (id < vocabulary.size) {
                val token = vocabulary[id]

                when {
                    token.startsWith("▁") -> {
                        // SentencePiece word boundary marker
                        sb.append(" ")
                        sb.append(token.substring(1))
                    }

                    token == "<unk>" || token == "<s>" || token == "</s>" || token == "<pad>" -> {
                        // Skip special tokens
                    }

                    else -> {
                        // Subword continuation - append directly
                        sb.append(token)
                    }
                }
            }
        }

        return sb.toString().trim()
    }

    fun close() {
        try {
            session.close()
            VoiceLogger.d(TAG, "Decoder session closed")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error closing decoder session", e)
        }
    }
}

/**
 * Result from a single decoder step.
 */
private data class DecoderStepResult(
    val predictedToken: Int,
    val newState1: FloatArray,
    val newState2: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as DecoderStepResult
        return predictedToken == other.predictedToken &&
                newState1.contentEquals(other.newState1) &&
                newState2.contentEquals(other.newState2)
    }

    override fun hashCode(): Int {
        var result = predictedToken
        result = 31 * result + newState1.contentHashCode()
        result = 31 * result + newState2.contentHashCode()
        return result
    }
}
