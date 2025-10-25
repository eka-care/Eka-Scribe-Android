package com.eka.voice2rx_sdk.audio

import android.content.Context
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import com.eka.voice2rx_sdk.data.remote.services.ModelDownloadApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ModelDownloader(private val context: Context) {

    companion object {
        private const val TAG = "ModelDownloader"
        private const val CDN_BASE_URL =
            "https://github.com/divyesh11/squim-models/releases/download/v1.0.6/"
        private const val MODEL_NAME = "squim_objective.onnx"
        private const val ETAG_FILE_NAME = "squim_objective.etag"
    }

    private val api: ModelDownloadApi by lazy {
        createRetrofitApi()
    }

    /**
     * Create Retrofit API with OkHttp client
     */
    private fun createRetrofitApi(): ModelDownloadApi {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            VoiceLogger.d(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(CDN_BASE_URL)
            .client(okHttpClient)
            .build()

        return retrofit.create(ModelDownloadApi::class.java)
    }

    /**
     * Get local model file path
     */
    fun getLocalModelFile(): File {
        val modelDir = File(context.filesDir, "models")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return File(modelDir, MODEL_NAME)
    }

    /**
     * Get ETag file
     */
    private fun getETagFile(): File {
        val modelDir = File(context.filesDir, "models")
        return File(modelDir, ETAG_FILE_NAME)
    }

    /**
     * Get locally stored ETag
     */
    private fun getLocalETag(): String? {
        val etagFile = getETagFile()
        return if (etagFile.exists()) {
            etagFile.readText().trim()
        } else {
            null
        }
    }

    /**
     * Save ETag locally
     */
    private fun saveLocalETag(etag: String) {
        val etagFile = getETagFile()
        etagFile.writeText(etag)
        VoiceLogger.d(TAG, "Saved ETag: $etag")
    }

    /**
     * Check if model exists locally
     */
    fun isModelDownloaded(): Boolean {
        val modelFile = getLocalModelFile()
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Download or update model using Retrofit with ETag
     */
    suspend fun downloadModelIfNeeded(): File {
        val modelFile = getLocalModelFile()
        val localETag = getLocalETag()

        VoiceLogger.d(TAG, "Checking model... Local ETag: $localETag")

        try {
            // Make request with ETag (if available)
            val response = api.downloadModel(localETag)

            when (response.code()) {
                304 -> {
                    // Not Modified - use cached version
                    VoiceLogger.d(TAG, "Model not modified (304) - using cached version")
                    return modelFile
                }

                200 -> {
                    // New content available - download it
                    val newETag = response.headers()["ETag"]?.trim('"')
                    VoiceLogger.d(TAG, "Downloading new model. New ETag: $newETag")
                    if (newETag == localETag) {
                        VoiceLogger.d(TAG, "Model is already up to date")
                        return modelFile
                    }

                    val responseBody = response.body()
                        ?: throw RuntimeException("Response body is null")

                    // Download to temp file
                    val tempFile = File(modelFile.parent, "${MODEL_NAME}.tmp")
                    downloadToFile(responseBody, tempFile)

                    // Verify file
                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        throw RuntimeException("Downloaded file is invalid")
                    }

                    // Replace old file with new one
                    if (modelFile.exists()) {
                        modelFile.delete()
                    }

                    if (!tempFile.renameTo(modelFile)) {
                        throw RuntimeException("Failed to rename temp file")
                    }

                    // Save new ETag
                    if (newETag != null) {
                        saveLocalETag(newETag)
                    }

                    VoiceLogger.d(
                        TAG,
                        "Model downloaded successfully. Size: ${modelFile.length() / (1024 * 1024)}MB"
                    )
                    return modelFile
                }

                else -> {
                    throw RuntimeException("Unexpected response code: ${response.code()}")
                }
            }

        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error downloading model", e)

            // If we have a cached model, use it despite the error
            if (modelFile.exists() && modelFile.length() > 0) {
                VoiceLogger.w(TAG, "Using cached model due to download error")
                return modelFile
            }

            throw RuntimeException(
                "Failed to download model and no cache available: ${e.message}",
                e
            )
        }
    }

    /**
     * Download response body to file with progress
     */
    private fun downloadToFile(responseBody: okhttp3.ResponseBody, targetFile: File) {
        val contentLength = responseBody.contentLength()
        VoiceLogger.d(TAG, "Starting download. Size: ${contentLength / (1024 * 1024)}MB")

        responseBody.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastLogTime = System.currentTimeMillis()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    // Log progress every second
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 1000) {
                        val progress = if (contentLength > 0) {
                            (totalBytesRead * 100 / contentLength).toInt()
                        } else {
                            -1
                        }
                        VoiceLogger.d(
                            TAG,
                            "Download progress: $progress% (${totalBytesRead / (1024 * 1024)}MB)"
                        )
                        lastLogTime = currentTime
                    }
                }
                output.flush()
            }
        }
    }

    /**
     * Force download latest model (ignore ETag)
     */
    suspend fun forceDownloadLatestModel(): File {
        VoiceLogger.d(TAG, "Force downloading latest model...")

        // Clear local ETag to force download
        getETagFile().delete()

        return downloadModelIfNeeded()
    }

    /**
     * Clear cached model
     */
    fun clearCache() {
        try {
            getLocalModelFile().delete()
            getETagFile().delete()
            VoiceLogger.d(TAG, "Model cache cleared")
        } catch (e: Exception) {
            VoiceLogger.e(TAG, "Error clearing cache", e)
        }
    }

    /**
     * Get model info
     */
    fun getModelInfo(): ModelInfo {
        val modelFile = getLocalModelFile()
        val etagFile = getETagFile()

        return ModelInfo(
            exists = modelFile.exists(),
            sizeBytes = if (modelFile.exists()) modelFile.length() else 0L,
            etag = if (etagFile.exists()) etagFile.readText().trim() else "unknown",
            path = modelFile.absolutePath
        )
    }
}

/**
 * Model information data class
 */
data class ModelInfo(
    val exists: Boolean,
    val sizeBytes: Long,
    val etag: String,
    val path: String
) {
    val sizeMB: Float get() = sizeBytes / (1024f * 1024f)
}