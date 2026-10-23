package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.requests.UpsertDocumentRequest
import com.eka.scribesdk.data.remote.models.responses.CONTEXT
import com.eka.scribesdk.data.remote.models.responses.CONTEXT_NAME
import com.eka.scribesdk.data.remote.models.responses.contextPlaceholderId
import com.eka.scribesdk.data.remote.models.responses.DocumentEnvelopeResponse
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Saves edited documents for Sessions API sessions:
 * `POST /voice/api/v1/documents {session_id, document_id}` -> pre-signed URL -> PUT the content.
 *
 * Mirrors the web client: the content is stored base64-encoded with `Content-Type: text/plain`
 * (that is how the web editor and GET /sessions read it back). [SessionData.data] is already base64.
 * On a failed PUT it fetches a fresh URL once and retries.
 *
 * Like the web editor, a note already published to the EMR (`publish.emr_webhook.status ==
 * "success"`) is marked unpublished before its content changes, so the EMR is not left holding
 * a version that no longer matches the note.
 *
 * A session without a context document shows an empty Context note ([contextPlaceholderId]);
 * saving it first creates the document (`type: context`, as the web does), then saves the text.
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
        private const val STATUS_PUBLISHED = "success"
        private const val STATUS_UNPUBLISHED = "unpublished"

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

    /** sessionId -> context document created here, so a second save does not create another. */
    private val createdContexts = ConcurrentHashMap<String, String>()
    private val createContextLock = Mutex()

    /** Saves each item; fails on the first document that can't be saved. */
    suspend fun save(sessionId: String, updatedData: List<SessionData>): Result<Boolean> {
        for (item in updatedData) {
            if (item.documentId.isBlank()) {
                return Result.failure(IllegalArgumentException("documentId is required to save ${item.templateId}"))
            }
            val target = if (item.documentId == contextPlaceholderId(sessionId)) {
                val documentId = contextDocumentId(sessionId).getOrElse { return Result.failure(it) }
                item.copy(documentId = documentId)
            } else {
                item
            }
            val saved = saveOne(sessionId, target)
            if (saved.isFailure) return saved
        }
        return Result.success(true)
    }

    /** The context document made for [sessionId] here, created on first use. */
    private suspend fun contextDocumentId(sessionId: String): Result<String> = createContextLock.withLock {
        createdContexts[sessionId]?.let { return Result.success(it) }
        val request = UpsertDocumentRequest(sessionId = sessionId, type = CONTEXT, documentName = CONTEXT_NAME)
        when (val response = apiService.upsertDocument(gson.toJsonTree(request).asJsonObject)) {
            is NetworkResponse.Success -> {
                val documentId = response.body.data?.documentId
                    ?: return Result.failure(Exception("No context document returned for $sessionId"))
                logger.info(TAG, "Context document created: $documentId (session $sessionId)")
                createdContexts[sessionId] = documentId
                Result.success(documentId)
            }

            is NetworkResponse.ServerError ->
                Result.failure(Exception(response.body.readableMessage("Create context failed (HTTP ${response.code})")))

            is NetworkResponse.NetworkError -> Result.failure(response.error)
            is NetworkResponse.UnknownError -> Result.failure(response.error)
        }
    }

    private suspend fun saveOne(sessionId: String, item: SessionData): Result<Boolean> {
        repeat(2) { attempt ->
            val document = fetchDocument(sessionId, item.documentId).getOrElse { return Result.failure(it) }
            val url = document.presignedUrl
            if (url.isNullOrBlank()) return Result.failure(Exception("No upload URL returned for ${item.documentId}"))
            if (attempt == 0 && document.isPublishedToEmr()) unpublish(sessionId, item.documentId)
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

    private fun DocumentEnvelopeResponse.DocumentData.isPublishedToEmr(): Boolean {
        val webhook = publish?.get("emr_webhook")?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val status = webhook.get("status")?.takeIf { it.isJsonPrimitive }?.asString
        return status.equals(STATUS_PUBLISHED, ignoreCase = true)
    }

    /** Mirrors the web's unpublish; a failure is logged and the edit is still saved. */
    private suspend fun unpublish(sessionId: String, documentId: String) {
        val publish = JsonObject().apply {
            add("emr_webhook", JsonObject().apply {
                addProperty("status", STATUS_UNPUBLISHED)
                addProperty("updated_at", System.currentTimeMillis() / 1000)
            })
        }
        val request = UpsertDocumentRequest(sessionId = sessionId, documentId = documentId, publish = publish)
        val body = gson.toJsonTree(request).asJsonObject
        when (val response = apiService.upsertDocument(body)) {
            is NetworkResponse.Success -> logger.info(TAG, "Unpublished $documentId before saving the edit")
            is NetworkResponse.ServerError ->
                logger.warn(TAG, "Unpublish failed for $documentId: ${response.body.readableMessage("HTTP ${response.code}")}")

            is NetworkResponse.NetworkError -> logger.warn(TAG, "Unpublish failed for $documentId", response.error)
            is NetworkResponse.UnknownError -> logger.warn(TAG, "Unpublish failed for $documentId", response.error)
        }
    }

    private suspend fun fetchDocument(
        sessionId: String,
        documentId: String
    ): Result<DocumentEnvelopeResponse.DocumentData> {
        val body = gson.toJsonTree(UpsertDocumentRequest(sessionId = sessionId, documentId = documentId)).asJsonObject
        return when (val response = apiService.upsertDocument(body)) {
            is NetworkResponse.Success -> {
                val data = response.body.data
                if (data == null) Result.failure(Exception("No document returned for $documentId"))
                else Result.success(data)
            }

            is NetworkResponse.ServerError ->
                Result.failure(Exception(response.body.readableMessage("Update document failed (HTTP ${response.code})")))

            is NetworkResponse.NetworkError -> Result.failure(response.error)
            is NetworkResponse.UnknownError -> Result.failure(response.error)
        }
    }
}
