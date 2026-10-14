package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.requests.UpsertDocumentRequest
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Saves edited documents for Sessions API sessions:
 * `POST /voice/api/v1/documents {session_id, document_id}` -> pre-signed URL -> PUT the content.
 *
 * Mirrors the web client: the content is stored base64-encoded with `Content-Type: text/plain`
 * (that is how the web editor and GET /sessions read it back). [SessionData.data] is already base64.
 * On a failed PUT it fetches a fresh URL once and retries.
 */
internal class SessionsDocumentWriter(
    private val apiService: SessionsApiService,
    private val logger: Logger,
    /** PUTs [body] to a pre-signed URL; returns true on 2xx. Replaceable in tests. */
    private val putContent: suspend (url: String, body: String) -> Boolean = { url, body -> defaultPut(url, body) }
) {
    companion object {
        private const val TAG = "SessionsDocumentWriter"
        private const val CONTENT_TYPE = "text/plain"

        // Plain client: pre-signed URLs carry their own auth and reject an extra Authorization header
        private val plainClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        private suspend fun defaultPut(url: String, body: String): Boolean = withContext(Dispatchers.IO) {
            // Bytes, not String: a String body would send "text/plain; charset=utf-8" and could
            // break a URL signed for exactly "text/plain"
            val request = Request.Builder()
                .url(url)
                .put(body.toByteArray(Charsets.UTF_8).toRequestBody(CONTENT_TYPE.toMediaType()))
                .build()
            plainClient.newCall(request).execute().use { it.isSuccessful }
        }
    }

    private val gson = Gson()

    /** Saves each item; fails on the first document that can't be saved. */
    suspend fun save(sessionId: String, updatedData: List<SessionData>): Result<Boolean> {
        for (item in updatedData) {
            if (item.documentId.isBlank()) {
                return Result.failure(IllegalArgumentException("documentId is required to save ${item.templateId}"))
            }
            val saved = saveOne(sessionId, item)
            if (saved.isFailure) return saved
        }
        return Result.success(true)
    }

    private suspend fun saveOne(sessionId: String, item: SessionData): Result<Boolean> {
        repeat(2) { attempt ->
            val url = fetchUploadUrl(sessionId, item.documentId).getOrElse { return Result.failure(it) }
            val ok = try {
                putContent(url, item.data)
            } catch (e: Exception) {
                logger.warn(TAG, "PUT failed for ${item.documentId}: ${e.message}", e)
                false
            }
            if (ok) {
                logger.info(TAG, "Document saved: ${item.documentId} (session $sessionId)")
                return Result.success(true)
            }
            logger.warn(TAG, "PUT rejected for ${item.documentId}, attempt ${attempt + 1}/2")
        }
        return Result.failure(Exception("Failed to upload document content for ${item.documentId}"))
    }

    private suspend fun fetchUploadUrl(sessionId: String, documentId: String): Result<String> {
        val body = gson.toJsonTree(UpsertDocumentRequest(sessionId = sessionId, documentId = documentId)).asJsonObject
        return when (val response = apiService.upsertDocument(body)) {
            is NetworkResponse.Success -> {
                val url = response.body.data?.presignedUrl
                if (url.isNullOrBlank()) Result.failure(Exception("No upload URL returned for $documentId"))
                else Result.success(url)
            }

            is NetworkResponse.ServerError ->
                Result.failure(Exception(response.body.readableMessage("Update document failed (HTTP ${response.code})")))

            is NetworkResponse.NetworkError -> Result.failure(response.error)
            is NetworkResponse.UnknownError -> Result.failure(response.error)
        }
    }
}
