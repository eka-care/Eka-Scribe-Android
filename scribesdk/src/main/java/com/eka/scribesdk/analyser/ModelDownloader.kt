package com.eka.scribesdk.analyser

import com.eka.scribesdk.analyser.ModelDownloader.Companion.CDN_BASE_URL
import com.eka.scribesdk.common.logging.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Downloads and caches the SQUIM ONNX model from CDN with ETag-based validation.
 *
 * On first init, the model is downloaded from [CDN_BASE_URL]. On subsequent inits,
 * the stored ETag is sent via If-None-Match header — if the server responds 304,
 * the cached model is reused without re-downloading.
 */
internal class ModelDownloader(
    private val filesDir: File,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "ModelDownloader"
        private const val CDN_BASE_URL =
            "https://github.com/divyesh11/squim-models/releases/download/v1.0.6/"
        private const val MODEL_NAME = "squim_objective.onnx"
        private const val ETAG_FILE_NAME = "squim_objective.etag"
        private const val MODEL_DIR_NAME = "models"
    }

    private val _stateFlow = MutableStateFlow<AnalyserState>(AnalyserState.Idle)
    val stateFlow: StateFlow<AnalyserState> = _stateFlow.asStateFlow()

    private val api: ModelDownloadApi by lazy { createRetrofitApi() }

    private fun createRetrofitApi(): ModelDownloadApi {
        val loggingInterceptor = HttpLoggingInterceptor { message ->
            logger.debug(TAG, message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(CDN_BASE_URL)
            .client(okHttpClient)
            .build()

        return retrofit.create(ModelDownloadApi::class.java)
    }

    fun getLocalModelFile(): File {
        val modelDir = File(filesDir, MODEL_DIR_NAME)
        if (!modelDir.exists()) {
            modelDir.mkdirs()
        }
        return File(modelDir, MODEL_NAME)
    }

    private fun getETagFile(): File {
        val modelDir = File(filesDir, MODEL_DIR_NAME)
        return File(modelDir, ETAG_FILE_NAME)
    }

    private fun getLocalETag(): String? {
        val etagFile = getETagFile()
        return if (etagFile.exists()) {
            etagFile.readText().trim()
        } else {
            null
        }
    }

    private fun saveLocalETag(etag: String) {
        val etagFile = getETagFile()
        etagFile.writeText(etag)
        logger.debug(TAG, "Saved ETag: $etag")
    }

    fun isModelDownloaded(): Boolean {
        val modelFile = getLocalModelFile()
        return modelFile.exists() && modelFile.length() > 0
    }

    /**
     * Returns the model file path if the model is downloaded and valid, null otherwise.
     */
    fun getModelPath(): String? {
        val modelFile = getLocalModelFile()
        return if (modelFile.exists() && modelFile.length() > 0) {
            modelFile.absolutePath
        } else {
            null
        }
    }

    /**
     * Downloads the model if needed, using ETag for cache validation.
     * Updates [stateFlow] with progress during download.
     *
     * @return the downloaded model file
     * @throws RuntimeException if download fails and no cached model is available
     */
    suspend fun downloadModelIfNeeded(): File {
        val modelFile = getLocalModelFile()
        val localETag = getLocalETag()

        logger.info(TAG, "Checking model... Local ETag: $localETag")
        _stateFlow.value = AnalyserState.Downloading(0)

        try {
            val response = api.downloadModel(localETag)

            when (response.code()) {
                304 -> {
                    logger.info(TAG, "Model not modified (304) - using cached version")
                    _stateFlow.value = AnalyserState.Ready(modelFile.absolutePath)
                    return modelFile
                }

                200 -> {
                    val newETag = response.headers()["ETag"]?.trim('"')
                    logger.info(TAG, "Downloading new model. New ETag: $newETag")

                    if (newETag == localETag && modelFile.exists() && modelFile.length() > 0) {
                        logger.info(TAG, "Model is already up to date")
                        _stateFlow.value = AnalyserState.Ready(modelFile.absolutePath)
                        return modelFile
                    }

                    val responseBody = response.body()
                        ?: throw RuntimeException("Response body is null")

                    val tempFile = File(modelFile.parent, "${MODEL_NAME}.tmp")
                    downloadToFile(responseBody, tempFile)

                    if (!tempFile.exists() || tempFile.length() == 0L) {
                        throw RuntimeException("Downloaded file is invalid")
                    }

                    if (modelFile.exists()) {
                        modelFile.delete()
                    }

                    if (!tempFile.renameTo(modelFile)) {
                        throw RuntimeException("Failed to rename temp file")
                    }

                    if (newETag != null) {
                        saveLocalETag(newETag)
                    }

                    logger.info(
                        TAG,
                        "Model downloaded successfully. Size: ${modelFile.length() / (1024 * 1024)}MB"
                    )
                    _stateFlow.value = AnalyserState.Ready(modelFile.absolutePath)
                    return modelFile
                }

                else -> {
                    throw RuntimeException("Unexpected response code: ${response.code()}")
                }
            }
        } catch (e: Exception) {
            logger.error(TAG, "Error downloading model", e)

            if (modelFile.exists() && modelFile.length() > 0) {
                logger.warn(TAG, "Using cached model due to download error")
                _stateFlow.value = AnalyserState.Ready(modelFile.absolutePath)
                return modelFile
            }

            _stateFlow.value = AnalyserState.Failed(e.message ?: "Unknown error")
            throw RuntimeException(
                "Failed to download model and no cache available: ${e.message}",
                e
            )
        }
    }

    private fun downloadToFile(responseBody: ResponseBody, targetFile: File) {
        val contentLength = responseBody.contentLength()
        logger.info(TAG, "Starting download. Size: ${contentLength / (1024 * 1024)}MB")

        responseBody.byteStream().use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalBytesRead = 0L
                var lastLogTime = System.currentTimeMillis()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalBytesRead += bytesRead

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastLogTime > 1000) {
                        val progress = if (contentLength > 0) {
                            (totalBytesRead * 100 / contentLength).toInt()
                        } else {
                            -1
                        }
                        _stateFlow.value = AnalyserState.Downloading(progress)
                        logger.debug(
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

    fun clearCache() {
        try {
            getLocalModelFile().delete()
            getETagFile().delete()
            logger.info(TAG, "Model cache cleared")
        } catch (e: Exception) {
            logger.error(TAG, "Error clearing cache", e)
        }
    }
}
