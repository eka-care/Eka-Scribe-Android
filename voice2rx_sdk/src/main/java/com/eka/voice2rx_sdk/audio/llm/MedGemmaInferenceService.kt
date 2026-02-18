package com.eka.voice2rx_sdk.audio.llm

import android.content.Context
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for running MedGemma-4B-IT GGUF inference to generate clinical notes from transcription.
 * Uses llama.cpp directly via JNI for on-device GGUF model processing.
 */
class MedGemmaInferenceService(private val context: Context) {

    companion object {
        private const val TAG = "MedGemmaInference"
        private const val CONTEXT_LENGTH = 2048
        private const val MAX_PREDICT_TOKENS = 1024
        private const val N_THREADS = 4
        private const val N_GPU_LAYERS = 0
        private const val MODEL_VERSION = "medgemma-4b-it-Q4_K_M"

        private const val CLINICAL_NOTES_PROMPT =
            """<start_of_turn>user
Generate clinical notes from the following doctor-patient conversation transcription. Include relevant sections like Chief Complaint, History of Present Illness, Assessment, and Plan.

Transcription: %s<end_of_turn>
<start_of_turn>model
"""
    }

    private var llamaBridge: LlamaCppBridge? = null
    private val modelDownloader = MedGemmaModelDownloader(context)
    private var isInitialized = false

    /**
     * Initialize the LLM inference with the MedGemma GGUF model.
     * Downloads model if not already present.
     * @param onProgress Callback for model download progress (0.0 to 1.0)
     */
    suspend fun initialize(onProgress: ((Float) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (isInitialized && llamaBridge != null) {
                VoiceLogger.d(TAG, "MedGemma LLM already initialized")
                return@withContext true
            }

            try {
                VoiceLogger.d(TAG, "Initializing MedGemma LLM...")

                // Download model if needed
                val modelFile = modelDownloader.downloadModelIfNeeded(onProgress)

                if (!modelFile.exists()) {
                    VoiceLogger.e(TAG, "MedGemma model file does not exist after download")
                    return@withContext false
                }

                VoiceLogger.d(TAG, "Model file size: ${modelFile.length() / (1024 * 1024)} MB")
                VoiceLogger.d(TAG, "Loading MedGemma GGUF model: ${modelFile.absolutePath}")

                // Initialize llama.cpp bridge
                val bridge = LlamaCppBridge()
                bridge.backendInit()

                // Load model directly from file path
                val loaded = bridge.loadModel(
                    modelPath = modelFile.absolutePath,
                    nCtx = CONTEXT_LENGTH,
                    nThreads = N_THREADS,
                    nGpuLayers = N_GPU_LAYERS
                )

                if (!loaded) {
                    VoiceLogger.e(TAG, "Failed to load MedGemma model via llama.cpp")
                    bridge.backendFree()
                    return@withContext false
                }

                llamaBridge = bridge
                isInitialized = true

                VoiceLogger.d(TAG, "MedGemma LLM initialized successfully")
                return@withContext true

            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Failed to initialize MedGemma LLM", e)
                isInitialized = false
                return@withContext false
            }
        }

    /**
     * Check if LLM is ready for inference
     */
    fun isReady(): Boolean = isInitialized && llamaBridge != null

    /**
     * Check if model is downloaded
     */
    fun isModelDownloaded(): Boolean = modelDownloader.isModelDownloaded()

    /**
     * Generate clinical notes from transcription text.
     *
     * @param transcript Combined transcription text from the session
     * @return Generated clinical notes in markdown format
     */
    suspend fun generateClinicalNotes(transcript: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isReady()) {
                VoiceLogger.w(TAG, "MedGemma LLM not initialized, attempting initialization...")
                if (!initialize()) {
                    return@withContext Result.failure(Exception("Failed to initialize MedGemma LLM"))
                }
            }

            if (transcript.isBlank()) {
                return@withContext Result.failure(Exception("Transcript is empty"))
            }

            try {
                VoiceLogger.d(
                    TAG,
                    "Generating clinical notes for transcript of length: ${transcript.length}"
                )

                // Format the prompt with the transcript
                val prompt = CLINICAL_NOTES_PROMPT.format(transcript)
                VoiceLogger.d(TAG, "prompt : $prompt")

                // Generate completion via llama.cpp
                val result = llamaBridge?.generateCompletion(prompt, MAX_PREDICT_TOKENS)?.trim()
                    ?: ""

                if (result.isBlank()) {
                    VoiceLogger.w(TAG, "MedGemma LLM returned empty response")
                    return@withContext Result.failure(Exception("LLM returned empty response"))
                }

                VoiceLogger.d(
                    TAG,
                    "Clinical notes generated successfully, length: ${result.length}"
                )
                return@withContext Result.success(result)

            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Error generating clinical notes", e)
                return@withContext Result.failure(e)
            }
        }

    /**
     * Get model info
     */
    fun getModelInfo(): MedGemmaModelInfo = modelDownloader.getModelInfo()

    /**
     * Get model version string
     */
    fun getModelVersion(): String = MODEL_VERSION

    /**
     * Release LLM resources
     */
    fun release() {
        try {
            llamaBridge?.unload()
            llamaBridge?.backendFree()
            llamaBridge = null
            isInitialized = false
            VoiceLogger.d(TAG, "MedGemma LLM released")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error releasing MedGemma LLM", e)
        }
    }

    /**
     * Clear downloaded model cache
     */
    fun clearModelCache() {
        modelDownloader.clearCache()
    }
}
