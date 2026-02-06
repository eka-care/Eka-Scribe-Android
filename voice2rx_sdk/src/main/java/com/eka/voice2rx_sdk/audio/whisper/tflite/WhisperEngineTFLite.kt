package com.eka.voice2rx_sdk.audio.whisper.tflite

import android.content.Context
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * TensorFlow Lite implementation of WhisperEngine.
 * Handles model loading, mel spectrogram computation, and inference.
 *
 * Supports hardware acceleration with fallback chain: GPU → NNAPI → CPU
 */
class WhisperEngineTFLite(private val context: Context) : WhisperEngine {

    companion object {
        private const val TAG = "WhisperEngineTFLite"
    }

    private val whisperUtil = WhisperUtil()
    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null
    private var isModelInitialized = false
    private var activeDelegate: String = "CPU"

    override fun isInitialized(): Boolean = isModelInitialized

    @Throws(IOException::class)
    override fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean): Boolean {
        try {
            // Load TFLite model with hardware acceleration
            loadModel(modelPath)
            Log.d(TAG, "Model loaded with $activeDelegate: $modelPath")

            // Load filters and vocabulary
            val vocabLoaded = whisperUtil.loadFiltersAndVocab(multilingual, vocabPath)
            if (vocabLoaded) {
                isModelInitialized = true
                Log.d(TAG, "Filters and vocab loaded: $vocabPath")
            } else {
                isModelInitialized = false
                Log.e(TAG, "Failed to load filters and vocab")
            }

            return isModelInitialized
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing WhisperEngineTFLite", e)
            throw IOException("Failed to initialize Whisper engine", e)
        }
    }

    override fun deinitialize() {
        interpreter?.close()
        interpreter = null

        // Close delegates
        gpuDelegate?.close()
        gpuDelegate = null
        nnapiDelegate?.close()
        nnapiDelegate = null

        isModelInitialized = false
        Log.d(TAG, "WhisperEngineTFLite deinitialized")
    }

    override fun transcribeFile(wavePath: String): String {
        if (!isModelInitialized) {
            Log.e(TAG, "Engine not initialized")
            return ""
        }

        Log.d(TAG, "Calculating mel spectrogram for: $wavePath")
        val melSpectrogram = getMelSpectrogram(wavePath)
        Log.d(TAG, "Mel spectrogram calculated")

        val result = runInference(melSpectrogram)
        Log.d(TAG, "Inference completed using $activeDelegate")

        return result
    }

    override fun transcribeBuffer(samples: FloatArray): String {
        if (!isModelInitialized) {
            Log.e(TAG, "Engine not initialized")
            return ""
        }

        // Pad or truncate samples to fixed input size
        val fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE
        val inputSamples = FloatArray(fixedInputSize)
        val copyLength = minOf(samples.size, fixedInputSize)
        System.arraycopy(samples, 0, inputSamples, 0, copyLength)

        // Calculate mel spectrogram
        val cores = Runtime.getRuntime().availableProcessors()
        val melSpectrogram = whisperUtil.getMelSpectrogram(inputSamples, inputSamples.size, cores)

        return runInference(melSpectrogram)
    }

    @Throws(IOException::class)
    private fun loadModel(modelPath: String) {
        val fileInputStream = FileInputStream(modelPath)
        val fileChannel = fileInputStream.channel
        val tfliteModel = fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            0,
            fileChannel.size()
        )

        // Configure interpreter options with hardware acceleration
        val options = Interpreter.Options().apply {
            numThreads = Runtime.getRuntime().availableProcessors()
        }

        // Try GPU delegate first
        var gpuSuccess = false
        try {
            gpuDelegate = GpuDelegate()
            options.addDelegate(gpuDelegate)

            // Test if GPU works by creating interpreter
            interpreter = Interpreter(tfliteModel, options)
            activeDelegate = "GPU"
            gpuSuccess = true
            Log.d(TAG, "GPU delegate enabled successfully")
        } catch (e: Throwable) {
            // Catch Throwable to handle NoClassDefFoundError
            Log.w(TAG, "GPU delegate failed: ${e.message}")
            try {
                gpuDelegate?.close()
            } catch (_: Throwable) {
            }
            gpuDelegate = null
            try {
                interpreter?.close()
            } catch (_: Throwable) {
            }
            interpreter = null
        }

        // Try NNAPI delegate if GPU failed
        if (!gpuSuccess) {
            try {
                val nnapiOptions = Interpreter.Options().apply {
                    numThreads = Runtime.getRuntime().availableProcessors()
                }
                nnapiDelegate = NnApiDelegate()
                nnapiOptions.addDelegate(nnapiDelegate)

                interpreter = Interpreter(tfliteModel, nnapiOptions)
                activeDelegate = "NNAPI"
                Log.d(TAG, "NNAPI delegate enabled successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "NNAPI delegate failed: ${e.message}")
                try {
                    nnapiDelegate?.close()
                } catch (_: Throwable) {
                }
                nnapiDelegate = null
                try {
                    interpreter?.close()
                } catch (_: Throwable) {
                }
                interpreter = null

                // Fallback to CPU
                val cpuOptions = Interpreter.Options().apply {
                    numThreads = Runtime.getRuntime().availableProcessors()
                }
                interpreter = Interpreter(tfliteModel, cpuOptions)
                activeDelegate = "CPU"
                Log.d(TAG, "Using CPU only")
            }
        }

        fileInputStream.close()
    }

    private fun getMelSpectrogram(wavePath: String): FloatArray {
        // Get samples from WAV file
        val samples = WaveUtil.getSamples(wavePath)

        // Pad or truncate to fixed input size
        val fixedInputSize = WhisperUtil.WHISPER_SAMPLE_RATE * WhisperUtil.WHISPER_CHUNK_SIZE
        val inputSamples = FloatArray(fixedInputSize)
        val copyLength = minOf(samples.size, fixedInputSize)
        System.arraycopy(samples, 0, inputSamples, 0, copyLength)

        val cores = Runtime.getRuntime().availableProcessors()
        return whisperUtil.getMelSpectrogram(inputSamples, inputSamples.size, cores)
    }

    private fun runInference(inputData: FloatArray): String {
        val interp = interpreter ?: return ""

        // Get input tensor info
        val inputTensor = interp.getInputTensor(0)
        val inputBuffer = TensorBuffer.createFixedSize(inputTensor.shape(), inputTensor.dataType())

        // Get output tensor info
        val outputTensor = interp.getOutputTensor(0)
        val outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(), DataType.FLOAT32)

        // Load input data
        val inputSize = inputTensor.shape().fold(1) { acc, dim -> acc * dim } * Float.SIZE_BYTES
        val inputBuf = ByteBuffer.allocateDirect(inputSize)
        inputBuf.order(ByteOrder.nativeOrder())
        for (value in inputData) {
            inputBuf.putFloat(value)
        }
        inputBuffer.loadBuffer(inputBuf)

        // Run inference
        interp.run(inputBuffer.buffer, outputBuffer.buffer)

        // Process results
        val outputLen = outputBuffer.intArray.size
        Log.d(TAG, "Output length: $outputLen")

        val result = StringBuilder()
        for (i in 0 until outputLen) {
            val token = outputBuffer.buffer.getInt()

            if (token == whisperUtil.tokenEOT) break

            // Get word for token, skip special tokens
            if (token < whisperUtil.tokenEOT) {
                val word = whisperUtil.getWordFromToken(token)
                if (word != null) {
                    result.append(word)
                }
            } else {
                // Log special tokens for debugging
                when (token) {
                    whisperUtil.tokenTranscribe -> Log.d(TAG, "Transcribe mode")
                    whisperUtil.tokenTranslate -> Log.d(TAG, "Translate mode")
                }
            }
        }

        return result.toString()
    }

    /**
     * Get the currently active delegate type.
     */
    fun getActiveDelegate(): String = activeDelegate
}
