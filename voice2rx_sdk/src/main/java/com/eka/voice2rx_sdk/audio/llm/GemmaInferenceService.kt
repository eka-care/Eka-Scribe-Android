package com.eka.voice2rx_sdk.audio.llm

import android.content.Context
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for running Gemma3-1B-IT LLM inference to generate clinical notes from transcription.
 * Uses MediaPipe LLM Inference API for on-device processing.
 */
class GemmaInferenceService(private val context: Context) {

    companion object {
        private const val TAG = "GemmaInferenceService"

        private const val CLINICAL_NOTES_PROMPT =
            """You are a medical scribe assistant. Extract clinical information from the following doctor-patient conversation and format it as markdown clinical notes.

**Transcription:**
%s

**Output Format:**
# Clinical Notes

## Chief Complaint
[Extract main reason for visit]

## History of Present Illness
[Relevant history discussed]

## Assessment
[Diagnosis or impressions if mentioned]

## Plan
[Treatment recommendations if mentioned]

## Medications
[Any medications discussed, or "None mentioned" if not discussed]

## Follow-up
[Next steps if mentioned, or "Not discussed" if not mentioned]

Generate the clinical notes now:"""
    }

    private var llmInference: LlmInference? = null
    private val modelDownloader = GemmaModelDownloader(context)
    private var isInitialized = false

    /**
     * Initialize the LLM inference with the Gemma model.
     * Downloads model if not already present.
     * @param onProgress Callback for model download progress (0.0 to 1.0)
     */
    suspend fun initialize(onProgress: ((Float) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            if (isInitialized && llmInference != null) {
                VoiceLogger.d(TAG, "LLM already initialized")
                return@withContext true
            }

            try {
                VoiceLogger.d(TAG, "Initializing Gemma LLM...")

                // Download model if needed
                val modelFile = modelDownloader.downloadModelIfNeeded(onProgress)

                if (!modelFile.exists()) {
                    VoiceLogger.e(TAG, "Model file does not exist after download")
                    return@withContext false
                }

                VoiceLogger.d(
                    TAG,
                    "Creating LLM Inference task with model: ${modelFile.absolutePath}"
                )

                // Configure LLM options
                val options = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelFile.absolutePath)
                    .setMaxTokens(1024)  // Limit output tokens for clinical notes
                    .setMaxTopK(40)
                    .build()

                // Create LLM inference instance
                llmInference = LlmInference.createFromOptions(context, options)
                isInitialized = true

                VoiceLogger.d(TAG, "Gemma LLM initialized successfully")
                return@withContext true

            } catch (e: Exception) {
                VoiceLogger.e(TAG, "Failed to initialize Gemma LLM", e)
                isInitialized = false
                return@withContext false
            }
        }

    /**
     * Check if LLM is ready for inference
     */
    fun isReady(): Boolean = isInitialized && llmInference != null

    /**
     * Check if model is downloaded
     */
    fun isModelDownloaded(): Boolean = modelDownloader.isModelDownloaded()

    /**
     * Generate clinical notes from transcription text.
     * This is a synchronous blocking call.
     *
     * @param transcript Combined transcription text from the session
     * @return Generated clinical notes in markdown format
     */
    suspend fun generateClinicalNotes(transcript: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (!isReady()) {
                VoiceLogger.w(TAG, "LLM not initialized, attempting initialization...")
                if (!initialize()) {
                    return@withContext Result.failure(Exception("Failed to initialize LLM"))
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

                // Generate response synchronously
                val result = llmInference?.generateResponse(prompt)

                if (result.isNullOrBlank()) {
                    VoiceLogger.w(TAG, "LLM returned empty response")
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
    fun getModelInfo(): GemmaModelInfo = modelDownloader.getModelInfo()

    /**
     * Release LLM resources
     */
    fun release() {
        try {
            llmInference?.close()
            llmInference = null
            isInitialized = false
            VoiceLogger.d(TAG, "Gemma LLM released")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error releasing Gemma LLM", e)
        }
    }

    /**
     * Clear downloaded model cache
     */
    fun clearModelCache() {
        modelDownloader.clearCache()
    }
}
