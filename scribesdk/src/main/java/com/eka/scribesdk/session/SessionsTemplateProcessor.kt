package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.DocumentRef
import com.eka.scribesdk.data.remote.models.responses.GetSessionResponse
import com.eka.scribesdk.data.remote.models.responses.SessionsErrorResponse
import com.eka.scribesdk.data.remote.models.responses.documentRefs
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.delay

/**
 * "Convert to another template" for Sessions API sessions, via the documented Process Template:
 * `POST /voice/v1/sessions/{id}/process/template/{template_id}` (202 + document_id), then
 * `GET /voice/v1/sessions/{id}?document_id=` until that note succeeds or fails. This is what the
 * web does; legacy sessions keep `POST voice/api/v1/transaction/{id}/convert-to-template/{template_id}`.
 */
internal class SessionsTemplateProcessor(
    private val apiService: SessionsApiService,
    private val logger: Logger,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {
    companion object {
        private const val TAG = "SessionsTemplateProcessor"

        // Same budget as the web SDK (60 x 2s)
        private const val DEFAULT_MAX_ATTEMPTS = 60
        private const val DEFAULT_INTERVAL_MS = 2_000L
        private const val HTTP_PROCESSING = 202
        private const val HTTP_TOO_MANY_REQUESTS = 429
    }

    /** Success once the new note is ready; the caller then reloads the session result. */
    suspend fun process(sessionId: String, templateId: String): Result<Boolean> {
        val documentId = when (val response = apiService.processTemplate(sessionId, templateId)) {
            is NetworkResponse.Success -> response.body.documentId
            is NetworkResponse.ServerError -> return failure(
                response.body.readableMessage("Process template failed (HTTP ${response.code})")
            )

            is NetworkResponse.NetworkError -> return Result.failure(response.error)
            is NetworkResponse.UnknownError -> return Result.failure(response.error)
        }
        logger.info(TAG, "Process template started: $sessionId, template=$templateId, document=$documentId")
        return awaitDocument(sessionId, templateId, documentId)
    }

    private suspend fun awaitDocument(sessionId: String, templateId: String, documentId: String?): Result<Boolean> {
        for (attempt in 0 until maxAttempts) {
            delay(intervalMs)
            val response = if (documentId != null) {
                apiService.getSessionDocument(sessionId, documentId)
            } else {
                apiService.getSession(sessionId)
            }
            when (val state = response.documentState(templateId, documentId)) {
                DocumentState.Ready -> return Result.success(true)
                is DocumentState.Failed -> return failure(state.message)
                DocumentState.Pending -> Unit
            }
        }
        return failure("Template generation timed out")
    }

    private fun NetworkResponse<GetSessionResponse, SessionsErrorResponse>.documentState(
        templateId: String,
        documentId: String?
    ): DocumentState = when (this) {
        is NetworkResponse.Success -> {
            if (code == HTTP_PROCESSING) {
                DocumentState.Pending
            } else {
                body.documentRefs().find(templateId, documentId)?.status.toDocumentState()
            }
        }

        is NetworkResponse.ServerError -> {
            val code = code
            if (code == null || code >= 500 || code == HTTP_TOO_MANY_REQUESTS) {
                DocumentState.Pending
            } else {
                DocumentState.Failed(body.readableMessage("Get session failed (HTTP $code)"))
            }
        }

        // Transient; keep polling within the budget
        is NetworkResponse.NetworkError, is NetworkResponse.UnknownError -> DocumentState.Pending
    }

    private fun List<DocumentRef>.find(templateId: String, documentId: String?): DocumentRef? =
        documentId?.let { id -> firstOrNull { it.documentId == id } } ?: lastOrNull { it.templateId == templateId }

    private fun String?.toDocumentState(): DocumentState = when (this?.lowercase()) {
        "success", "completed", "partial", "partial_success" -> DocumentState.Ready
        "failure", "failed", "error" -> DocumentState.Failed("Template generation failed")
        else -> DocumentState.Pending
    }

    private fun failure(message: String): Result<Boolean> {
        logger.warn(TAG, message)
        return Result.failure(Exception(message))
    }

    private sealed class DocumentState {
        data object Ready : DocumentState()
        data object Pending : DocumentState()
        data class Failed(val message: String) : DocumentState()
    }
}
