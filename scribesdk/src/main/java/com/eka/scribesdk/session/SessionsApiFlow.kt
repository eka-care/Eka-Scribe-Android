package com.eka.scribesdk.session

import com.eka.scribesdk.BuildConfig
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.requests.CreateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.EndSessionRequest
import com.eka.scribesdk.data.remote.models.requests.PatchSessionRequest
import com.eka.scribesdk.data.remote.models.responses.ResultStatus
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.readableMessage
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.CancellationException

/**
 * Session lifecycle on the EkaScribe Sessions API (`/voice/v1/sessions`):
 * create -> PATCH session_mode -> (chunks via SessionsChunkUploader) -> end.
 *
 * Maps onto the existing [TransactionStage] machine so recovery works unchanged:
 * INIT --create--> STOP --end--> COMMIT --(no API)--> ANALYZING --GET /sessions--> COMPLETED.
 */
internal class SessionsApiFlow(
    private val apiService: SessionsApiService,
    private val dataManager: DataManager,
    private val flavour: String,
    private val logger: Logger,
    private val resultFetcher: SessionsResultFetcher? = null
) {
    companion object {
        private const val TAG = "SessionsApiFlow"
        private const val UPLOAD_TYPE_CHUNKED = "chunked"
        private const val MAX_TEMPLATES = 2
        private const val AUTO_DETECT = "auto_detect"
        private const val STATUS_CANCELLED = "cancelled"
        private const val USER_STATUS_RECORDING_STARTED = "recording_started"
        private const val AUDIO_DURATION = "audio_duration"

        // Same cadence as the web client: every 2s; give up after 2 minutes
        private const val POLL_INTERVAL_MS = 2_000L
        private const val POLL_TIMEOUT_MS = 120_000L
        private const val POLL_MAX_ATTEMPTS = (POLL_TIMEOUT_MS / POLL_INTERVAL_MS).toInt()
        private val USABLE_STATES = setOf(ResultStatus.SUCCESS, ResultStatus.PARTIAL_COMPLETED)
    }

    // Default Gson drops null fields, so optional values are omitted from request bodies
    private val gson = Gson()

    /** `POST /sessions` then `PATCH session_mode`. On success the stage moves to STOP. */
    suspend fun createSession(sessionId: String, sessionConfig: SessionConfig): TransactionResult {
        val request = CreateSessionRequest(
            uploadType = UPLOAD_TYPE_CHUNKED,
            sessionId = sessionId,
            templates = toTemplateIds(sessionConfig),
            model = sessionConfig.modelType,
            languageHint = toLanguageHint(sessionConfig.languages),
            patientDetails = toPatientDetails(sessionConfig.patientDetails),
            additionalData = toAdditionalData(sessionConfig)
        )

        when (val response = apiService.createSession(request.toJson())) {
            is NetworkResponse.Success -> {
                val serverSessionId = response.body.sessionId
                if (!serverSessionId.isNullOrBlank() && serverSessionId != sessionId) {
                    // The local DB is keyed by our id; continuing would upload to the wrong session
                    logger.error(TAG, "Server returned a different session id: $serverSessionId (sent $sessionId)")
                    return TransactionResult.Error("Server returned a different session id")
                }
                logger.info(
                    TAG,
                    "Session created: $sessionId, storage=${response.body.storageProvider}, expires=${response.body.expiresAt}"
                )
            }

            is NetworkResponse.ServerError -> {
                // 409: already created by an earlier attempt (e.g. recovery after a crash) — continue
                if (response.code == 409) {
                    logger.info(TAG, "Session already exists, continuing: $sessionId")
                } else {
                    val message = response.body.readableMessage("Create session failed")
                    logger.error(TAG, "Create session server error ${response.code}: $sessionId - $message")
                    return TransactionResult.Error(message, code = response.body?.error?.code)
                }
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "Create session network error: $sessionId", response.error)
                return TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Create session unknown error: $sessionId", response.error)
                return TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }

        // session_mode is not part of the create body; it can only be set by PATCH before end.
        // user_status = recording_started as the web sends it, so a session that never reaches
        // End shows on web as "recording started" (recommit offered), not as a fresh session
        val modeResult = patchSession(
            sessionId,
            PatchSessionRequest(sessionMode = sessionConfig.mode, userStatus = USER_STATUS_RECORDING_STARTED)
        )
        if (modeResult is TransactionResult.Error) return modeResult

        dataManager.updateUploadStage(sessionId, TransactionStage.STOP.name)
        return TransactionResult.Success()
    }

    /**
     * `additional_data.audio_duration` (seconds), as the web sends after End, so web can show the
     * recording length. Best effort: failures are only logged.
     */
    private suspend fun reportAudioDuration(sessionId: String) {
        try {
            val durationMs = dataManager.getAllChunks(sessionId).sumOf { it.durationMs }
            if (durationMs <= 0) return
            val seconds = (durationMs + 500) / 1000
            val result = patchSession(
                sessionId,
                PatchSessionRequest(additionalData = mapOf(AUDIO_DURATION to seconds))
            )
            if (result is TransactionResult.Error) {
                logger.warn(TAG, "audio_duration not saved for $sessionId: ${result.message}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(TAG, "audio_duration not saved for $sessionId", e)
        }
    }

    /** `POST /sessions/{id}/end`. Starts server-side processing; the stage moves to COMMIT. */
    suspend fun endSession(sessionId: String): TransactionResult {
        val uploadedCount = dataManager.getUploadedChunks(sessionId).size
        val request = EndSessionRequest(audioFilesSent = uploadedCount)

        return when (val response = apiService.endSession(sessionId, request.toJson())) {
            is NetworkResponse.Success -> {
                dataManager.updateUploadStage(sessionId, TransactionStage.COMMIT.name)
                logger.info(
                    TAG,
                    "Session ended: $sessionId, sent=$uploadedCount, received=${response.body.audioFilesReceived}"
                )
                reportAudioDuration(sessionId)
                TransactionResult.Success()
            }

            is NetworkResponse.ServerError -> {
                val message = response.body.readableMessage("End session failed")
                // 400 "session already ended": an earlier end call succeeded (e.g. before a crash)
                if (response.code == 400 && message.contains("ended", ignoreCase = true)) {
                    dataManager.updateUploadStage(sessionId, TransactionStage.COMMIT.name)
                    logger.info(TAG, "Session already ended, continuing: $sessionId")
                    TransactionResult.Success()
                } else {
                    logger.error(TAG, "End session server error ${response.code}: $sessionId - $message")
                    TransactionResult.Error(message)
                }
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "End session network error: $sessionId", response.error)
                TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "End session unknown error: $sessionId", response.error)
                TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }
    }

    /**
     * Polls `GET /sessions/{id}` until processing finishes. 200 and 206 both complete the session
     * (206 = some templates failed); 410 means it expired. Moves the stage to COMPLETED or FAILURE.
     */
    suspend fun pollResult(sessionId: String): TransactionPollResult {
        val fetcher = resultFetcher
            ?: return TransactionPollResult.Failed("Sessions API result fetcher not configured")

        return when (val state = fetcher.poll(sessionId, POLL_MAX_ATTEMPTS, POLL_INTERVAL_MS)) {
            is SessionFetchState.Done -> {
                if (state.result.hasUsableOutput()) {
                    dataManager.updateUploadStage(sessionId, TransactionStage.COMPLETED.name)
                    logger.info(TAG, "Result ready: $sessionId (partial=${state.partial})")
                    TransactionPollResult.Success(state.result)
                } else {
                    dataManager.updateUploadStage(sessionId, TransactionStage.FAILURE.name)
                    logger.warn(TAG, "Session finished without usable output: $sessionId")
                    TransactionPollResult.Failed("Processing finished without any output")
                }
            }

            is SessionFetchState.Expired -> {
                dataManager.updateUploadStage(sessionId, TransactionStage.FAILURE.name)
                logger.warn(TAG, "Session expired: $sessionId")
                TransactionPollResult.Failed("Session expired before processing")
            }

            is SessionFetchState.Error -> {
                logger.error(TAG, "Result fetch failed: $sessionId - ${state.message}")
                TransactionPollResult.Failed(state.message)
            }

            // poll() never returns Processing; null means it timed out
            SessionFetchState.Processing, null -> TransactionPollResult.Timeout
        }
    }

    private fun ScribeResultResponse.hasUsableOutput(): Boolean {
        val results = data?.templateResults ?: return false
        return !results.transcript.isNullOrEmpty() ||
            results.custom.orEmpty().any { it?.status in USABLE_STATES } ||
            results.integration.orEmpty().any { it?.status in USABLE_STATES }
    }

    /** Marks the session cancelled on the server. Best effort: failures are only logged. */
    suspend fun cancelSession(sessionId: String) {
        val result = patchSession(
            sessionId,
            PatchSessionRequest(userStatus = STATUS_CANCELLED, processingStatus = STATUS_CANCELLED)
        )
        if (result is TransactionResult.Error) {
            logger.warn(TAG, "Cancel session failed (ignored): $sessionId - ${result.message}")
        }
    }

    private suspend fun patchSession(sessionId: String, request: PatchSessionRequest): TransactionResult {
        return when (val response = apiService.patchSession(sessionId, request.toJson())) {
            is NetworkResponse.Success -> TransactionResult.Success()
            is NetworkResponse.ServerError -> {
                val message = response.body.readableMessage("Update session failed")
                logger.error(TAG, "Patch session server error ${response.code}: $sessionId - $message")
                TransactionResult.Error(message)
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "Patch session network error: $sessionId", response.error)
                TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Patch session unknown error: $sessionId", response.error)
                TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }
    }

    private fun Any.toJson(): JsonObject = gson.toJsonTree(this).asJsonObject

    /** Template IDs, capped at the documented max of 2. */
    private fun toTemplateIds(sessionConfig: SessionConfig): List<String>? {
        val ids = sessionConfig.outputTemplates
            ?.map { it.templateId.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        if (ids.size > MAX_TEMPLATES) {
            logger.warn(TAG, "Sessions API allows $MAX_TEMPLATES templates, dropping: ${ids.drop(MAX_TEMPLATES)}")
        }
        return ids.take(MAX_TEMPLATES).ifEmpty { null }
    }

    /** ISO 639-1 codes as documented: "en-IN" -> "en", "hi" -> "hi"; "auto_detect" is kept. */
    private fun toLanguageHint(languages: List<String>): List<String>? =
        languages
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map {
                if (it.equals(AUTO_DETECT, ignoreCase = true)) {
                    AUTO_DETECT
                } else {
                    it.substringBefore('-').substringBefore('_').lowercase()
                }
            }
            .distinct()
            .ifEmpty { null }

    /** Same keys the web client sends; `oid` is indexed by the server. */
    private fun toPatientDetails(patient: PatientDetail?): Map<String, String>? {
        if (patient == null) return null
        return buildMap<String, String> {
            patient.patientId?.takeIf { it.isNotBlank() }?.let { put("oid", it) }
            patient.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
            patient.age?.let { put("age", it.toString()) }
            patient.biologicalSex?.takeIf { it.isNotBlank() }?.let { put("gender", it) }
        }.ifEmpty { null }
    }

    /** Fields with no first-class slot in the create body travel in additional_data. */
    private fun toAdditionalData(sessionConfig: SessionConfig): Map<String, String> =
        buildMap<String, String> {
            put("_flavour", flavour)
            put("sdk_version", BuildConfig.SDK_VERSION_NAME)
            sessionConfig.speciality?.takeIf { it.isNotBlank() }?.let { put("speciality", it) }
            sessionConfig.section?.takeIf { it.isNotBlank() }?.let { put("section", it) }
            sessionConfig.patientDetails?.visitId?.takeIf { it.isNotBlank() }?.let { put("visit_id", it) }
        }
}
