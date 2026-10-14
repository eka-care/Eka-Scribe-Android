package com.eka.scribesdk.data.remote.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Collections

/**
 * Uploads audio chunks to `POST /voice/v1/sessions/{session_id}/audio/{file_sequence}`
 * as raw bytes. Replaces [S3ChunkUploader] for sessions created through the Sessions API.
 *
 * Remote file names follow the documented `<base>_<sequence>.<ext>` pattern: `audio_0.mp3`,
 * `audio_1.mp3`… built from the 0-based chunk index, so local file names don't change.
 */
internal class SessionsChunkUploader(
    private val context: Context,
    private val apiService: SessionsApiService,
    private val maxRetryCount: Int = 2,
    private val logger: Logger
) : ChunkUploader {

    companion object {
        private const val TAG = "SessionsChunkUploader"
        private const val FILE_BASE_NAME = "audio"

        /** Remote name for a chunk, e.g. index 0 -> `audio_0.mp3`. */
        internal fun remoteFileName(chunkIndex: Int, extension: String): String =
            "${FILE_BASE_NAME}_$chunkIndex.$extension"
    }

    /** Tracks chunk IDs currently being uploaded to prevent double-enqueue. */
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
        // Full-recording uploads (index -1) have no place in the Sessions API
        if (metadata.chunkIndex < 0) {
            return UploadResult.Failure(
                error = "Full audio upload is not supported by the Sessions API",
                isRetryable = false
            )
        }

        if (!isNetworkAvailable()) {
            logger.debug(TAG, "No network available, skipping upload: ${metadata.chunkId}")
            return UploadResult.Failure(error = "No network available", isRetryable = true)
        }

        if (!inFlight.add(metadata.chunkId)) {
            logger.debug(TAG, "Chunk already in-flight, skipping: ${metadata.chunkId}")
            return UploadResult.Failure(error = "Chunk already being uploaded", isRetryable = false)
        }

        return try {
            uploadWithRetry(file, metadata, retryCount = 0)
        } finally {
            inFlight.remove(metadata.chunkId)
        }
    }

    override fun clearCache() {
        inFlight.clear()
    }

    private suspend fun uploadWithRetry(
        file: File,
        metadata: UploadMetadata,
        retryCount: Int
    ): UploadResult {
        val result = doUpload(file, metadata, retryCount)
        return if (result is UploadResult.Failure && result.isRetryable && retryCount < maxRetryCount) {
            logger.info(TAG, "Retrying upload (${retryCount + 1}/$maxRetryCount): ${metadata.chunkId}")
            uploadWithRetry(file, metadata, retryCount + 1)
        } else {
            result
        }
    }

    private suspend fun doUpload(
        file: File,
        metadata: UploadMetadata,
        retryCount: Int
    ): UploadResult {
        if (!file.exists()) {
            logger.error(TAG, "File does not exist: ${file.absolutePath}")
            return UploadResult.Failure("File does not exist", isRetryable = false)
        }

        val remoteName = remoteFileName(metadata.chunkIndex, file.extension.ifBlank { "mp3" })
        logger.info(
            TAG,
            "Uploading chunk: ${metadata.chunkId} as $remoteName, attempt=$retryCount"
        )

        // contentType = null: no Content-Type header, the server infers it from the extension
        val body = file.asRequestBody(null)

        return when (val response = apiService.uploadAudio(metadata.sessionId, remoteName, body)) {
            is NetworkResponse.Success -> {
                logger.info(TAG, "Upload completed: ${metadata.chunkId} -> $remoteName")
                UploadResult.Success(url = "${metadata.sessionId}/$remoteName")
            }

            is NetworkResponse.ServerError -> {
                val code = response.code
                val message = response.body.readableMessage("Upload failed (HTTP $code)")
                // 5xx and 429 are temporary; other 4xx (session ended, bad format, 413 too large) are not
                val retryable = code == null || code >= 500 || code == 429
                logger.warn(TAG, "Upload server error $code: ${metadata.chunkId} - $message")
                UploadResult.Failure(message, isRetryable = retryable)
            }

            is NetworkResponse.NetworkError -> {
                logger.warn(TAG, "Upload network error: ${metadata.chunkId}", response.error)
                UploadResult.Failure(response.error.message ?: "Network error", isRetryable = true)
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Upload unknown error: ${metadata.chunkId}", response.error)
                UploadResult.Failure(response.error.message ?: "Unknown error", isRetryable = true)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
