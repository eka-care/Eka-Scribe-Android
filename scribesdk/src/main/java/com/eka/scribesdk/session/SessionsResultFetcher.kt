package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.models.responses.GetSessionResponse
import com.eka.scribesdk.data.remote.models.responses.AndroidBase64Codec
import com.eka.scribesdk.data.remote.models.responses.Base64Codec
import com.eka.scribesdk.data.remote.models.responses.CONTEXT
import com.eka.scribesdk.data.remote.models.responses.documentRefs
import com.eka.scribesdk.data.remote.models.responses.toDocumentNames
import com.eka.scribesdk.data.remote.models.responses.toScribeResultResponse
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Outcome of one `GET /voice/v1/sessions/{id}` call. */
internal sealed class SessionFetchState {
    /** 202: keep polling. */
    data object Processing : SessionFetchState()

    /** 200 / 206: result converted to the legacy shape used by the existing mappers. */
    data class Done(val result: ScribeResultResponse, val partial: Boolean) : SessionFetchState()

    /** 410: the session expired before processing; create a new one. */
    data object Expired : SessionFetchState()

    /**
     * [notOnSessionsApi]: the Sessions API does not know this session id: 404, or 400/422 for an id
     * it cannot accept (old legacy ids are longer than its 32-char limit).
     */
    data class Error(
        val message: String,
        val retryable: Boolean,
        val notOnSessionsApi: Boolean = false
    ) : SessionFetchState()
}

/**
 * Reads results from the Sessions API. Shared by the recording flow (TransactionManager) and
 * the public result APIs (ScribeRepository) for sessions created on the Sessions API.
 */
internal class SessionsResultFetcher(
    private val apiService: SessionsApiService,
    private val logger: Logger,
    /** Downloads the context text (Get Session lists the context document without it); null = not shown. */
    private val contextReader: SessionContextReader? = null,
    private val codec: Base64Codec = AndroidBase64Codec,
    /** templateId -> name for a session's templates; used as note titles. */
    private val templateNames: suspend (sessionId: String, templateIds: List<String>) -> Map<String, String> =
        { _, _ -> emptyMap() }
) {
    companion object {
        private const val TAG = "SessionsResultFetcher"
        private const val HTTP_PARTIAL = 206
        private const val HTTP_PROCESSING = 202
        private const val HTTP_GONE = 410
        private val HTTP_UNKNOWN_SESSION = setOf(400, 404, 422)
        private const val MAX_BACKOFF_MS = 10_000L
    }

    suspend fun fetch(sessionId: String): SessionFetchState =
        when (val response = apiService.getSession(sessionId)) {
            is NetworkResponse.Success -> when {
                response.code == HTTP_PROCESSING -> SessionFetchState.Processing
                response.body.status.equals("failed", ignoreCase = true) ->
                    SessionFetchState.Error(response.body.message ?: "Processing failed", retryable = false)

                else -> SessionFetchState.Done(
                    result = toResult(sessionId, response.body),
                    partial = response.code == HTTP_PARTIAL
                )
            }

            is NetworkResponse.ServerError -> {
                val code = response.code
                if (code == HTTP_GONE) {
                    SessionFetchState.Expired
                } else {
                    val message = response.body.readableMessage("Get session failed (HTTP $code)")
                    SessionFetchState.Error(
                        message,
                        retryable = code == null || code >= 500 || code == 429,
                        notOnSessionsApi = code in HTTP_UNKNOWN_SESSION
                    )
                }
            }

            is NetworkResponse.NetworkError ->
                SessionFetchState.Error(response.error.message ?: "Network error", retryable = true)

            is NetworkResponse.UnknownError ->
                SessionFetchState.Error(response.error.message ?: "Unknown error", retryable = true)
        }

    /**
     * Note titles: each note's own name (renames included) from "List Session Documents"; template
     * names are looked up only for notes that call did not name, e.g. when it fails.
     */
    private suspend fun toResult(sessionId: String, body: GetSessionResponse): ScribeResultResponse {
        val documentNames = documentNames(sessionId)
        val unnamedTemplateIds = body.documentRefs()
            .filter { (_, documentId) -> documentId == null || documentId !in documentNames }
            .map { (templateId, _) -> templateId }
            .distinct()
        val templateNames = if (unnamedTemplateIds.isEmpty()) {
            emptyMap()
        } else {
            templateNames(sessionId, unnamedTemplateIds)
        }
        return body.toScribeResultResponse(
            codec = codec,
            templateNames = templateNames,
            documentNames = documentNames,
            contextTexts = contextTexts(body)
        )
    }

    /** document_id -> text for the session's context documents that could be read. */
    private suspend fun contextTexts(body: GetSessionResponse): Map<String, String> {
        val reader = contextReader ?: return emptyMap()
        return body.documentRefs()
            .filter { it.documentType == CONTEXT }
            .mapNotNull { ref -> ref.documentId?.let { id -> reader.read(id)?.let { id to it } } }
            .toMap()
    }

    /** document_id -> current note name; empty on any failure, so titles fall back to template names. */
    private suspend fun documentNames(sessionId: String): Map<String, String> =
        try {
            when (val response = apiService.listSessionDocuments(sessionId)) {
                is NetworkResponse.Success -> response.body.toDocumentNames()
                else -> {
                    logger.warn(TAG, "Note names unavailable for $sessionId; using template names")
                    emptyMap()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "Note names unavailable for $sessionId; using template names", e)
            emptyMap()
        }

    /**
     * Polls until the session is done, expired or fails for good. Network errors back off
     * (doubling, up to 10s). Returns null on timeout (after [maxAttempts] calls).
     */
    suspend fun poll(sessionId: String, maxAttempts: Int, intervalMs: Long): SessionFetchState? {
        var backoffMs = intervalMs
        repeat(maxAttempts) { attempt ->
            logger.debug(TAG, "Polling session ${attempt + 1}/$maxAttempts: $sessionId")
            val state = fetch(sessionId)
            val waitMs = when (state) {
                is SessionFetchState.Processing -> {
                    backoffMs = intervalMs
                    intervalMs
                }

                is SessionFetchState.Error -> {
                    if (!state.retryable) return state
                    logger.warn(TAG, "Poll error (will retry): $sessionId - ${state.message}")
                    backoffMs.also { backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS) }
                }

                else -> return state
            }
            if (attempt < maxAttempts - 1) delay(waitMs)
        }
        logger.warn(TAG, "Poll timeout: $sessionId")
        return null
    }
}
