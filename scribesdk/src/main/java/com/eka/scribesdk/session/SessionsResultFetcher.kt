package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.models.responses.templateIds
import com.eka.scribesdk.data.remote.models.responses.toScribeResultResponse
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.delay

/** Outcome of one `GET /voice/v1/sessions/{id}` call. */
internal sealed class SessionFetchState {
    /** 202: keep polling. */
    data object Processing : SessionFetchState()

    /** 200 / 206: result converted to the legacy shape used by the existing mappers. */
    data class Done(val result: ScribeResultResponse, val partial: Boolean) : SessionFetchState()

    /** 410: the session expired before processing; create a new one. */
    data object Expired : SessionFetchState()

    data class Error(val message: String, val retryable: Boolean) : SessionFetchState()
}

/**
 * Reads results from the Sessions API. Shared by the recording flow (TransactionManager) and
 * the public result APIs (ScribeRepository) for sessions created on the Sessions API.
 */
internal class SessionsResultFetcher(
    private val apiService: SessionsApiService,
    private val logger: Logger,
    /** templateId -> name for a session's templates; used as note titles. */
    private val templateNames: suspend (sessionId: String, templateIds: List<String>) -> Map<String, String> =
        { _, _ -> emptyMap() }
) {
    companion object {
        private const val TAG = "SessionsResultFetcher"
        private const val HTTP_PARTIAL = 206
        private const val HTTP_PROCESSING = 202
        private const val HTTP_GONE = 410
        private const val MAX_BACKOFF_MS = 10_000L
    }

    suspend fun fetch(sessionId: String): SessionFetchState =
        when (val response = apiService.getSession(sessionId)) {
            is NetworkResponse.Success -> when {
                response.code == HTTP_PROCESSING -> SessionFetchState.Processing
                response.body.status.equals("failed", ignoreCase = true) ->
                    SessionFetchState.Error(response.body.message ?: "Processing failed", retryable = false)

                else -> SessionFetchState.Done(
                    result = response.body.toScribeResultResponse(
                        templateNames = templateNames(sessionId, response.body.templateIds())
                    ),
                    partial = response.code == HTTP_PARTIAL
                )
            }

            is NetworkResponse.ServerError -> {
                val code = response.code
                if (code == HTTP_GONE) {
                    SessionFetchState.Expired
                } else {
                    val message = response.body.readableMessage("Get session failed (HTTP $code)")
                    SessionFetchState.Error(message, retryable = code == null || code >= 500 || code == 429)
                }
            }

            is NetworkResponse.NetworkError ->
                SessionFetchState.Error(response.error.message ?: "Network error", retryable = true)

            is NetworkResponse.UnknownError ->
                SessionFetchState.Error(response.error.message ?: "Unknown error", retryable = true)
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
