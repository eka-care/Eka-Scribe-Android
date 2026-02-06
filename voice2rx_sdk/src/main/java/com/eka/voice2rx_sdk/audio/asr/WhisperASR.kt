package com.eka.voice2rx_sdk.audio.asr

import android.content.Context
import com.eka.voice2rx_sdk.audio.whisper.tflite.WhisperEngineTFLite
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * WhisperASR - Wrapper for TFLite Whisper speech recognition.
 * Handles initialization, transcription, and resource management.
 *
 * Follows the same pattern as SherpaASR for consistent API.
 */
class WhisperASR(private val context: Context) {

    companion object {
        private const val TAG = "WhisperASR"
    }

    private var whisperEngine: WhisperEngineTFLite? = null
    private val mutex = Mutex()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isCancelled = false

    /**
     * Initialize the WhisperASR with model and vocabulary files.
     *
     * @param modelPath Path to the .tflite model file
     * @param vocabPath Path to the vocabulary .bin file
     * @param multilingual Whether to use multilingual vocabulary (default: true)
     */
    suspend fun initialize(
        modelPath: String,
        vocabPath: String,
        multilingual: Boolean = true
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (isInitialized) {
                VoiceLogger.d(TAG, "Already initialized")
                return@withContext
            }

            try {
                VoiceLogger.d(TAG, "Initializing WhisperASR with model: $modelPath")

                whisperEngine = WhisperEngineTFLite(context)
                val success = whisperEngine?.initialize(modelPath, vocabPath, multilingual) ?: false

                if (success) {
                    isInitialized = true
                    isCancelled = false
                    VoiceLogger.d(TAG, "WhisperASR initialized successfully")
                } else {
                    throw IllegalStateException("Failed to initialize Whisper engine")
                }
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Failed to initialize WhisperASR", e)
                throw e
            }
        }
    }

    /**
     * Transcribe audio data (float array).
     * Audio should be mono, 16kHz, normalized to [-1, 1].
     */
    suspend fun transcribe(audioData: FloatArray): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            throw IllegalStateException("WhisperASR not initialized")
        }

        if (isCancelled) {
            return@withContext ""
        }

        mutex.withLock {
            try {
                val engine = whisperEngine ?: throw IllegalStateException("Engine is null")
                val result = engine.transcribeBuffer(audioData)
                result.trim()
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Transcription failed", e)
                throw e
            }
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
     * Transcribe audio from a WAV file.
     */
    suspend fun transcribeFile(wavePath: String): String = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            throw IllegalStateException("WhisperASR not initialized")
        }

        if (isCancelled) {
            return@withContext ""
        }

        mutex.withLock {
            try {
                val engine = whisperEngine ?: throw IllegalStateException("Engine is null")
                val result = engine.transcribeFile(wavePath)
                result.trim()
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "File transcription failed", e)
                throw e
            }
        }
    }

    /**
     * Check if the ASR is ready to transcribe.
     */
    fun isReady(): Boolean = isInitialized && whisperEngine?.isInitialized() == true

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
        VoiceLogger.d(TAG, "Releasing WhisperASR")
        isCancelled = true

        mutex.withLock {
            try {
                if (isInitialized) {
                    whisperEngine?.deinitialize()
                    whisperEngine = null
                    isInitialized = false
                    VoiceLogger.d(TAG, "WhisperASR released")
                }
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error releasing WhisperASR", e)
            }
        }
    }
}
