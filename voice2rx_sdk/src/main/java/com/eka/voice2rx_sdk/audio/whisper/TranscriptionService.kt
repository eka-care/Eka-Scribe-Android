package com.eka.voice2rx_sdk.audio.whisper

import android.content.Context
import com.eka.voice2rx_sdk.audio.asr.WhisperASR
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.local.db.Voice2RxDatabase
import com.eka.voice2rx_sdk.data.local.db.entities.ChunkTranscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Service for transcribing audio chunks using Whisper TFLite and persisting results to database.
 */
class TranscriptionService(
    private val context: Context,
    private val sessionId: String,
    private val language: String = "en",
    private val modelName: String = WhisperModelDownloader.DEFAULT_MODEL,
    private val vocabName: String = WhisperModelDownloader.DEFAULT_VOCAB,
    private val multilingual: Boolean = true
) {
    companion object {
        private const val TAG = "TranscriptionService"
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dao by lazy { Voice2RxDatabase.getDatabase(context).getVoice2RxDao() }
    private val modelDownloader by lazy { WhisperModelDownloader(context) }

    private val whisperASR by lazy { WhisperASR(context) }
    private val initMutex = Mutex()
    private val transcribeMutex = Mutex()

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isCancelled = false

    @Volatile
    private var chunkIndex = 0

    /**
     * Initialize Whisper TFLite ASR. Copies model from assets if needed.
     * @param onProgress Progress callback for model preparation (0-100)
     */
    suspend fun initialize(onProgress: ((Int) -> Unit)? = null) {
        initMutex.withLock {
            if (isInitialized && whisperASR.isReady()) {
                VoiceLogger.d(TAG, "Already initialized")
                return
            }

            try {
                VoiceLogger.d(TAG, "Initializing TranscriptionService with model: $modelName")

                // Ensure model and vocab are copied from assets
                val (modelPath, vocabPath) = modelDownloader.ensureModelFromAssets(
                    modelName = modelName,
                    vocabName = vocabName,
                    onProgress = onProgress
                )
                VoiceLogger.d(TAG, "Model ready: $modelPath, Vocab ready: $vocabPath")

                // Initialize WhisperASR
                whisperASR.initialize(modelPath, vocabPath, multilingual)
                isInitialized = true
                isCancelled = false

                VoiceLogger.d(TAG, "TranscriptionService initialized successfully")
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Failed to initialize TranscriptionService", e)
                throw e
            }
        }
    }

    /**
     * Check if the service is ready for transcription
     */
    fun isReady(): Boolean = isInitialized && whisperASR.isReady()

    /**
     * Transcribe audio chunk and save to database.
     *
     * @param audioData ShortArray of audio samples (mono, 16kHz)
     * @param fileId Optional file ID to link transcription to VoiceFile
     * @param startTime Start time of the chunk
     * @param endTime End time of the chunk
     * @return ChunkTranscription entity that was saved
     */
    suspend fun transcribeAndSave(
        audioData: ShortArray,
        fileId: String? = null,
        startTime: String,
        endTime: String
    ): ChunkTranscription {
        if (!isReady()) {
            VoiceLogger.e(TAG, "TranscriptionService not initialized")
            throw IllegalStateException("TranscriptionService not initialized. Call initialize() first.")
        }

        return transcribeMutex.withLock {
            try {
                VoiceLogger.d(
                    TAG,
                    "Transcribing chunk ${chunkIndex + 1}: samples=${audioData.size}"
                )

                // Check cancellation
                if (isCancelled) {
                    throw IllegalStateException("Transcription cancelled")
                }

                // Transcribe using WhisperASR
                val transcribedText = whisperASR.transcribe(audioData)
                VoiceLogger.d(TAG, "Transcription result: '$transcribedText'")

                // Create entity
                val transcription = ChunkTranscription(
                    transcriptionId = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    fileId = fileId,
                    text = transcribedText,
                    startTime = startTime,
                    endTime = endTime,
                    language = language,
                    chunkIndex = chunkIndex++
                )

                // Save to database
                dao.insertChunkTranscription(transcription)
                VoiceLogger.d(TAG, "Transcription saved: ${transcription.transcriptionId}")

                transcription
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error transcribing chunk", e)
                throw e
            }
        }
    }

    /**
     * Async version of transcribeAndSave that doesn't block the caller
     */
    fun transcribeAndSaveAsync(
        audioData: ShortArray,
        fileId: String? = null,
        startTime: String,
        endTime: String,
        onComplete: ((ChunkTranscription) -> Unit)? = null,
        onError: ((Exception) -> Unit)? = null
    ) {
        if (isCancelled) {
            VoiceLogger.d(TAG, "Transcription skipped - service cancelled")
            return
        }

        VoiceLogger.d(TAG, "transcribeAndSaveAsync called. Data size: ${audioData.size}")
        scope.launch {
            try {
                if (isCancelled) {
                    VoiceLogger.d(TAG, "Transcription skipped before start - cancelled")
                    return@launch
                }

                val result = transcribeAndSave(audioData, fileId, startTime, endTime)
                VoiceLogger.d(TAG, "Async transcription completed successfully")
                onComplete?.invoke(result)
            } catch (e: Exception) {
                if (isCancelled) {
                    VoiceLogger.d(TAG, "Transcription cancelled during execution")
                } else {
                    VoiceLogger.e(TAG, "Async transcription failed", e)
                    onError?.invoke(e)
                }
            }
        }
    }

    /**
     * Get all transcriptions for the current session
     */
    suspend fun getTranscriptions(): List<ChunkTranscription> {
        return dao.getChunkTranscriptionsBySessionId(sessionId)
    }

    /**
     * Get full transcription text for the session
     */
    suspend fun getFullTranscriptionText(): String {
        return getTranscriptions()
            .sortedBy { it.chunkIndex }
            .joinToString(" ") { it.text }
            .trim()
    }

    /**
     * Release resources and cancel pending transcriptions
     */
    suspend fun release() {
        VoiceLogger.d(TAG, "Releasing TranscriptionService")
        isCancelled = true
        job.cancel()

        initMutex.withLock {
            try {
                whisperASR.release()
                isInitialized = false
                VoiceLogger.d(TAG, "TranscriptionService released")
            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error releasing TranscriptionService", e)
            }
        }
    }
}
