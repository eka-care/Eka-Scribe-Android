package com.eka.voice2rx_sdk.audio.llm

import android.content.Context
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Handles downloading and managing the MedGemma-4B-IT GGUF model.
 * Model is downloaded from HuggingFace on first session start (lazy loading).
 */
class MedGemmaModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "MedGemmaModelDownloader"
        private const val MEDGEMMA_MODEL_URL =
            "https://huggingface.co/lmstudio-community/medgemma-4b-it-GGUF/resolve/main/medgemma-4b-it-Q4_K_M.gguf"
        private const val MODEL_FILE_NAME = "medgemma-4b-it-Q4_K_M.gguf"
        private const val MODEL_DIR_NAME = "medgemma"
    }

    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            VoiceLogger.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(1800, TimeUnit.SECONDS) // 30 min for large file download (~3.5GB)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Get local model directory
     */
    private fun getModelDir(): File {
        val modelDir = File(context.filesDir, MODEL_DIR_NAME)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return modelDir
    }

    /**
     * Get local model file path
     */
    fun getLocalModelFile(): File {
        return File(getModelDir(), MODEL_FILE_NAME)
    }

    /**
     * Check if model exists locally
     */
    fun isModelDownloaded(): Boolean {
        val modelFile = getLocalModelFile()
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Download model if not already present
     * @param onProgress Callback for download progress (0.0 to 1.0)
     * @return The local model file
     */
    suspend fun downloadModelIfNeeded(onProgress: ((Float) -> Unit)? = null): File {
        val modelFile = getLocalModelFile()

        if (isModelDownloaded()) {
            VoiceLogger.d(TAG, "MedGemma model already downloaded: ${modelFile.absolutePath}")
            onProgress?.invoke(1.0f)
            return modelFile
        }

        VoiceLogger.d(TAG, "Starting MedGemma model download from HuggingFace...")

        try {
            val request = Request.Builder()
                .url(MEDGEMMA_MODEL_URL)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                throw RuntimeException("Failed to download MedGemma model: HTTP ${response.code}")
            }

            val responseBody = response.body
                ?: throw RuntimeException("Response body is null")

            val contentLength = responseBody.contentLength()
            VoiceLogger.d(TAG, "MedGemma model size: ${contentLength / (1024 * 1024)} MB")

            // Download to temp file first
            val tempFile = File(getModelDir(), "${MODEL_FILE_NAME}.tmp")
            downloadToFile(responseBody, tempFile, contentLength, onProgress)

            // Verify file
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw RuntimeException("Downloaded file is invalid")
            }

            // Replace with final file
            if (modelFile.exists()) {
                modelFile.delete()
            }

            if (!tempFile.renameTo(modelFile)) {
                throw RuntimeException("Failed to rename temp file")
            }

            VoiceLogger.d(TAG, "MedGemma model downloaded successfully: ${modelFile.absolutePath}")
            VoiceLogger.d(TAG, "MedGemma model size: ${modelFile.length() / (1024 * 1024)} MB")

            return modelFile

        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error downloading MedGemma model", e)

            // Clean up temp file on failure
            File(getModelDir(), "${MODEL_FILE_NAME}.tmp").delete()

            throw RuntimeException("Failed to download MedGemma model: ${e.message}", e)
        }
    }

    /**
     * Download response body to file with progress tracking
     */
    private fun downloadToFile(
        responseBody: okhttp3.ResponseBody,
        targetFile: File,
        contentLength: Long,
        onProgress: ((Float) -> Unit)?
    ) {
        responseBody.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastProgressUpdate = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // Update progress every 1%
                    if (contentLength > 0) {
                        val progress = totalBytesRead.toFloat() / contentLength
                        val progressPercent = (progress * 100).toLong()

                        if (progressPercent > lastProgressUpdate) {
                            lastProgressUpdate = progressPercent
                            onProgress?.invoke(progress)
                            VoiceLogger.d(TAG, "Download progress: $progressPercent%")
                        }
                    }
                }
                output.flush()
            }
        }
        onProgress?.invoke(1.0f)
    }

    /**
     * Delete cached model
     */
    fun clearCache() {
        try {
            getLocalModelFile().delete()
            File(getModelDir(), "${MODEL_FILE_NAME}.tmp").delete()
            VoiceLogger.d(TAG, "MedGemma model cache cleared")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error clearing MedGemma cache", e)
        }
    }

    /**
     * Get model info
     */
    fun getModelInfo(): MedGemmaModelInfo {
        val modelFile = getLocalModelFile()
        return MedGemmaModelInfo(
            exists = modelFile.exists(),
            sizeBytes = if (modelFile.exists()) modelFile.length() else 0L,
            path = modelFile.absolutePath
        )
    }
}

/**
 * MedGemma model information
 */
data class MedGemmaModelInfo(
    val exists: Boolean,
    val sizeBytes: Long,
    val path: String
) {
    val sizeMB: Float get() = sizeBytes / (1024f * 1024f)
}
