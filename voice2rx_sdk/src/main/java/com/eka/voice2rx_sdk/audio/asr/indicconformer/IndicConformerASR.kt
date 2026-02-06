package com.eka.voice2rx_sdk.audio.asr.indicconformer

import ai.onnxruntime.OrtEnvironment
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * IndicConformer ASR - Hindi Speech-to-Text using ONNX Runtime.
 *
 * Uses IndicConformer RNNT model with:
 * - Conformer encoder (encoder.onnx)
 * - RNNT decoder-joint network (decoder_joint.onnx)
 * - SentencePiece vocabulary (tokens.txt)
 *
 * Includes periodic model reload to release native memory.
 */
class IndicConformerASR {

    companion object {
        private const val TAG = "IndicConformerASR"
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SIZE_SECONDS = 15
        private const val CHUNK_SIZE_SAMPLES = SAMPLE_RATE * CHUNK_SIZE_SECONDS

        // Reload sessions after this many inferences to release native memory
        private const val RELOAD_AFTER_INFERENCES = 5
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var encoder: IndicConformerEncoder? = null
    private var decoder: RNNTDecoder? = null
    private var melProcessor: MelSpectrogramProcessor? = null
    private var vocabulary: List<String>? = null

    // Store paths for reload
    private var encoderPath: String? = null
    private var decoderPath: String? = null

    // Inference counter for periodic reload
    private var inferenceCount = 0

    private val mutex = Mutex()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isCancelled = false

    init {
        VoiceLogger.d(TAG, "IndicConformerASR created")
    }

    /**
     * Initialize the IndicConformer ASR with model files.
     *
     * @param encoderPath Path to encoder.onnx
     * @param decoderPath Path to decoder_joint.onnx
     * @param tokensPath Path to tokens.txt
     */
    suspend fun initialize(
        encoderPath: String,
        decoderPath: String,
        tokensPath: String
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInitialized) {
                VoiceLogger.d(TAG, "Already initialized")
                return@withContext
            }

            try {
                VoiceLogger.d(TAG, "Initializing IndicConformer ASR from files...")
                val startTime = System.currentTimeMillis()

                // Store paths for reload
                this@IndicConformerASR.encoderPath = encoderPath
                this@IndicConformerASR.decoderPath = decoderPath

                // Load vocabulary
                vocabulary = loadVocabulary(tokensPath)
                VoiceLogger.d(TAG, "Loaded vocabulary with ${vocabulary?.size} tokens")

                // Initialize ONNX Runtime environment
                ortEnvironment = OrtEnvironment.getEnvironment()

                // Initialize mel spectrogram processor
                melProcessor = MelSpectrogramProcessor()

                // Load encoder & decoder
                loadSessions()

                isInitialized = true
                isCancelled = false
                inferenceCount = 0

                val loadTime = System.currentTimeMillis() - startTime
                VoiceLogger.d(TAG, "IndicConformer ASR initialized in ${loadTime}ms")
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Failed to initialize IndicConformer ASR", e)
                release()
                throw e
            }
        }
    }

    /**
     * Load or reload encoder and decoder sessions.
     */
    private fun loadSessions() {
        val env = ortEnvironment ?: throw IllegalStateException("ORT environment not initialized")
        val vocab = vocabulary ?: throw IllegalStateException("Vocabulary not loaded")
        val encPath = encoderPath ?: throw IllegalStateException("Encoder path not set")
        val decPath = decoderPath ?: throw IllegalStateException("Decoder path not set")

        encoder = IndicConformerEncoder(env, encPath)
        decoder = RNNTDecoder(env, decPath, vocab)
    }

    /**
     * Close and reload sessions to release native memory.
     */
    private fun reloadSessions() {
        VoiceLogger.d(TAG, "Reloading sessions to release native memory...")
        val startTime = System.currentTimeMillis()

        // Close existing sessions
        encoder?.close()
        decoder?.close()
        encoder = null
        decoder = null

        // Force GC to release native memory
        System.gc()

        // Reload
        loadSessions()

        VoiceLogger.d(TAG, "Sessions reloaded in ${System.currentTimeMillis() - startTime}ms")
    }

    /**
     * Transcribe audio data (float array).
     * Audio should be mono, 16kHz, normalized to [-1, 1].
     *
     * For audio longer than 15 seconds, it will be processed in chunks.
     */
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            throw IllegalStateException("IndicConformer ASR not initialized")
        }

        if (isCancelled) {
            return@withContext ""
        }

        mutex.withLock {
            try {
                val totalSamples = audioData.size
                val durationSeconds = totalSamples / SAMPLE_RATE.toFloat()
                VoiceLogger.d(TAG, "Transcribing ${durationSeconds}s audio ($totalSamples samples)")

                // Process in chunks if longer than 15 seconds
                if (totalSamples <= CHUNK_SIZE_SAMPLES) {
                    return@withLock transcribeChunk(audioData)
                }

                // Split into 15-second chunks with 1-second overlap
                val overlapSamples = SAMPLE_RATE // 1 second overlap
                val results = mutableListOf<String>()
                var offset = 0

                while (offset < totalSamples && !isCancelled) {
                    val chunkEnd = minOf(offset + CHUNK_SIZE_SAMPLES, totalSamples)
                    val chunk = audioData.copyOfRange(offset, chunkEnd)

                    VoiceLogger.d(TAG, "Processing chunk at offset $offset (${chunk.size} samples)")
                    val chunkResult = transcribeChunk(chunk)
                    if (chunkResult.isNotEmpty()) {
                        results.add(chunkResult)
                    }

                    offset += CHUNK_SIZE_SAMPLES - overlapSamples
                }

                // Join results (simple concatenation - could be improved with overlap handling)
                results.joinToString(" ").trim()
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Transcription failed", e)
                throw e
            }
        }
    }

    /**
     * Transcribe a single audio chunk.
     */
    private fun transcribeChunk(audioData: FloatArray): String {
        // Check if we need to reload sessions to release native memory
        inferenceCount++
        if (inferenceCount >= RELOAD_AFTER_INFERENCES) {
            reloadSessions()
            inferenceCount = 0
        }

        val processor = melProcessor ?: throw IllegalStateException("Mel processor is null")
        val enc = encoder ?: throw IllegalStateException("Encoder is null")
        val dec = decoder ?: throw IllegalStateException("Decoder is null")

        try {
            // 1. Compute mel spectrogram
            val startTime = System.currentTimeMillis()
            val melSpec = processor.compute(audioData)
            val numFrames = melSpec[0].size
            VoiceLogger.d(
                TAG,
                "Mel: ${melSpec.size}x$numFrames in ${System.currentTimeMillis() - startTime}ms"
            )

            // 2. Flatten for ONNX input
            val flatFeatures = processor.flattenForOnnx(melSpec)

            // 3. Run encoder
            val encoderOutput = enc.run(flatFeatures, numFrames)

            // 4. Run RNNT greedy decoding
            val transcript = dec.decode(encoderOutput)
            VoiceLogger.d(TAG, "Transcribed (inf#$inferenceCount): '${transcript.take(30)}...'")

            return transcript
        } finally {
            // Clear mel processor buffers
            processor.clearBuffers()
            // Hint to GC
            System.gc()
        }
    }


    /**
     * Transcribe audio data (short array).
     * Converts to float and normalizes to [-1, 1].
     */
    suspend fun transcribe(audioData: ShortArray): String {
        val floatData = FloatArray(audioData.size) { i ->
            audioData[i] / 32768f
        }
        return transcribe(floatData)
    }

    /**
     * Check if the ASR is ready to transcribe.
     */
    fun isReady(): Boolean = isInitialized && encoder != null && decoder != null

    /**
     * Cancel pending transcription.
     */
    fun cancel() {
        isCancelled = true
    }

    /**
     * Release resources.
     */
    suspend fun release() {
        VoiceLogger.d(TAG, "Releasing IndicConformer ASR")
        isCancelled = true

        mutex.withLock {
            try {
                decoder?.close()
                decoder = null

                encoder?.close()
                encoder = null

                melProcessor = null
                vocabulary = null

                ortEnvironment?.close()
                ortEnvironment = null

                isInitialized = false
                VoiceLogger.d(TAG, "IndicConformer ASR released")
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error releasing IndicConformer ASR", e)
            }
        }
    }

    /**
     * Load vocabulary from tokens.txt.
     * Each line is one token. Token ID = line index (0-indexed).
     */
    private fun loadVocabulary(tokensPath: String): List<String> {
        VoiceLogger.d(TAG, "Loading vocabulary from $tokensPath")
        val file = File(tokensPath)
        if (!file.exists()) {
            VoiceLogger.e(TAG, "Tokens file not found: $tokensPath")
            throw IllegalArgumentException("Tokens file not found: $tokensPath")
        }

        return file.readLines().map { it.trim() }
    }
}

