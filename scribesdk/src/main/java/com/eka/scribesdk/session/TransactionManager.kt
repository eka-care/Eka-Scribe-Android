package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.OutputTemplate
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.deleteFile
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.local.db.entity.UploadState
import com.eka.scribesdk.data.remote.models.requests.ChunkData
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.OutputFormatTemplate
import com.eka.scribesdk.data.remote.models.requests.PatientDetails
import com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
import com.eka.scribesdk.data.remote.models.responses.ResultStatus
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.google.gson.Gson
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


internal class TransactionManager(
    private val apiService: ScribeApiService,
    private val dataManager: DataManager,
    private val chunkUploader: ChunkUploader,
    internal val bucketName: String,
    private val maxUploadRetries: Int,
    private val pollMaxRetries: Int,
    private val pollDelayMs: Long,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "TransactionManager"
    }

    private val gson = Gson()

    /**
     * Call the init transaction API before recording starts.
     * Builds the s3_url and serializes the request for recovery.
     */
    suspend fun initTransaction(
        sessionId: String,
        sessionConfig: SessionConfig,
        folderName: String
    ): TransactionResult {
        val s3Url = "s3://$bucketName/$folderName/$sessionId"

        val request = InitTransactionRequest(
            inputLanguage = sessionConfig.languages,
            mode = sessionConfig.mode,
            outputFormatTemplate = sessionConfig.outputTemplates?.map {
                OutputFormatTemplate(
                    templateId = it.templateId,
                    type = it.templateType,
                    name = it.templateName
                )
            },
            s3Url = s3Url,
            section = sessionConfig.section,
            speciality = sessionConfig.speciality,
            modelType = sessionConfig.modelType,
            patientDetails = sessionConfig.patientDetails?.let {
                PatientDetails(
                    age = it.age,
                    biologicalSex = it.biologicalSex,
                    name = it.name,
                    patientId = it.patientId,
                    visitId = it.visitId
                )
            }
        )

        return when (val response = apiService.initTransaction(sessionId, request)) {
            is NetworkResponse.Success -> {
                val bid = response.body.bId ?: ""
                // Single DB call for post-API data (stage + bid)
                dataManager.updateStageAndBid(sessionId, TransactionStage.STOP.name, bid)
                logger.info(
                    TAG,
                    "Init transaction success: $sessionId, bid=$bid, folder=$folderName"
                )
                TransactionResult.Success(folderName = folderName, bid = bid)
            }

            is NetworkResponse.ServerError -> {
                val errorMsg =
                    response.body?.error?.message ?: response.body?.message ?: "Init failed"
                logger.error(TAG, "Init transaction server error: $sessionId - $errorMsg")
                TransactionResult.Error(errorMsg)
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "Init transaction network error: $sessionId", response.error)
                TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Init transaction unknown error: $sessionId", response.error)
                TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }
    }

    /**
     * Call the stop transaction API after recording stops.
     * Sends the list of uploaded audio files and their timing info.
     */
    suspend fun stopTransaction(sessionId: String): TransactionResult {
        val uploadedChunks = dataManager.getUploadedChunks(sessionId)

        val audioFiles = uploadedChunks.map { it.fileName }
        val chunkInfo = uploadedChunks.map { chunk ->
            mapOf(
                chunk.fileName to ChunkData(
                    startTime = chunk.startTimeMs / 1000.0,
                    endTime = chunk.endTimeMs / 1000.0
                )
            )
        }

        val request = StopTransactionRequest(
            audioFiles = audioFiles,
            chunkInfo = chunkInfo
        )

        return when (val response = apiService.stopTransaction(sessionId, request)) {
            is NetworkResponse.Success -> {
                dataManager.updateUploadStage(sessionId, TransactionStage.COMMIT.name)
                logger.info(TAG, "Stop transaction success: $sessionId")
                TransactionResult.Success()
            }

            is NetworkResponse.ServerError -> {
                val errorMsg = response.body?.error ?: response.body?.message ?: "Stop failed"
                logger.error(TAG, "Stop transaction server error: $sessionId - $errorMsg")
                TransactionResult.Error(errorMsg)
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "Stop transaction network error: $sessionId", response.error)
                TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Stop transaction unknown error: $sessionId", response.error)
                TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }
    }

    /**
     * Call the commit transaction API after stop succeeds.
     * Sends the same audio files list (only uploaded ones).
     */
    suspend fun commitTransaction(sessionId: String): TransactionResult {
        val uploadedChunks = dataManager.getUploadedChunks(sessionId)

        val audioFiles = uploadedChunks.filter { true }.map { it.fileName }
        val request = StopTransactionRequest(
            audioFiles = audioFiles,
            chunkInfo = emptyList()
        )

        return when (val response = apiService.commitTransaction(sessionId, request)) {
            is NetworkResponse.Success -> {
                dataManager.updateUploadStage(sessionId, TransactionStage.ANALYZING.name)
                logger.info(TAG, "Commit transaction success: $sessionId")
                TransactionResult.Success()
            }

            is NetworkResponse.ServerError -> {
                val errorMsg = response.body?.error ?: response.body?.message ?: "Commit failed"
                logger.error(TAG, "Commit transaction server error: $sessionId - $errorMsg")
                TransactionResult.Error(errorMsg)
            }

            is NetworkResponse.NetworkError -> {
                logger.error(TAG, "Commit transaction network error: $sessionId", response.error)
                TransactionResult.Error("Network error: ${response.error.message}")
            }

            is NetworkResponse.UnknownError -> {
                logger.error(TAG, "Commit transaction unknown error: $sessionId", response.error)
                TransactionResult.Error("Unknown error: ${response.error.message}")
            }
        }
    }

    /**
     * Poll for transcription results.
     * HTTP 202 = still processing, keep polling.
     * HTTP 200 with SUCCESS status = done.
     */
    suspend fun pollResult(sessionId: String): TransactionPollResult {
        val successStates = setOf(ResultStatus.SUCCESS, ResultStatus.PARTIAL_COMPLETED)
        val failureStates = setOf(ResultStatus.FAILURE)

        repeat(pollMaxRetries) { attempt ->
            logger.debug(TAG, "Polling result attempt ${attempt + 1}/$pollMaxRetries: $sessionId")

            when (val response = apiService.getTransactionResult(sessionId)) {
                is NetworkResponse.Success -> {
                    // Check if HTTP 202 (still processing)
                    if (response.code == 202) {
                        delay(pollDelayMs)
                        return@repeat
                    }

                    val outputStatuses = response.body.data?.output?.mapNotNull { it?.status }

                    if (outputStatuses?.any { it in successStates } == true) {
                        dataManager.updateUploadStage(sessionId, TransactionStage.COMPLETED.name)
                        logger.info(TAG, "Poll result success: $sessionId")
                        return TransactionPollResult.Success(response.body)
                    }

                    if (outputStatuses?.all { it in failureStates } == true) {
                        dataManager.updateUploadStage(sessionId, TransactionStage.FAILURE.name)
                        logger.warn(TAG, "Poll result failure: $sessionId")
                        return TransactionPollResult.Failed("Transcription processing failed")
                    }

                    // Still in progress
                    delay(pollDelayMs)
                }

                is NetworkResponse.ServerError -> {
                    logger.warn(TAG, "Poll server error attempt ${attempt + 1}: $sessionId")
                    delay(pollDelayMs)
                }

                is NetworkResponse.NetworkError -> {
                    logger.warn(TAG, "Poll network error attempt ${attempt + 1}: $sessionId")
                    delay(pollDelayMs)
                }

                is NetworkResponse.UnknownError -> {
                    logger.warn(TAG, "Poll unknown error attempt ${attempt + 1}: $sessionId")
                    delay(pollDelayMs)
                }
            }
        }

        logger.warn(TAG, "Poll timeout: $sessionId")
        return TransactionPollResult.Timeout
    }

    /**
     * Retry uploading any failed chunks for the given session.
     * Also resets and retries chunks whose retry count has been exhausted
     * (e.g. from a previous network outage).
     * Returns true if all chunks are now uploaded.
     */
    suspend fun retryFailedUploads(sessionId: String): Boolean {
        // Clear in-flight cache for retries
        chunkUploader.clearCache()
        val allChunks = dataManager.getAllChunks(sessionId = sessionId)
        val allChunksToRetry = allChunks.filterNot { it.uploadState == UploadState.SUCCESS.name }

        if (allChunksToRetry.isEmpty()) {
            return dataManager.areAllChunksUploaded(sessionId)
        }

        val session = dataManager.getSession(sessionId)
        val folderName = session?.folderName ?: ""
        val bid = session?.bid ?: ""

        logger.info(TAG, "Retrying ${allChunksToRetry.size} failed chunks for session: $sessionId")

        for (chunk in allChunksToRetry) {
            val file = File(chunk.filePath)
            if (!file.exists()) {
                logger.warn(TAG, "Chunk file missing, skipping retry: ${chunk.chunkId}")
                continue
            }

            dataManager.markInProgress(chunk.chunkId)

            val metadata = UploadMetadata(
                chunkId = chunk.chunkId,
                sessionId = chunk.sessionId,
                chunkIndex = chunk.chunkIndex,
                fileName = chunk.fileName,
                folderName = folderName,
                bid = bid
            )

            when (val result = chunkUploader.upload(file, metadata)) {
                is UploadResult.Success -> {
                    dataManager.markUploaded(chunk.chunkId)
                    deleteFile(file = file, logger = logger)
                    logger.info(TAG, "Retry upload success & cleaned: ${chunk.chunkId}")
                }

                is UploadResult.Failure -> {
                    dataManager.markFailed(chunk.chunkId)
                    logger.warn(TAG, "Retry upload failed: ${chunk.chunkId} - ${result.error}")
                }
            }
        }

        return dataManager.areAllChunksUploaded(sessionId)
    }

    /**
     * Idempotent resume from any stage. Used for session recovery.
     *
     * @param force If true, proceed with stop/commit even if some chunks
     *              failed to upload. If false, returns an error when not
     *              all chunks are uploaded.
     */
    suspend fun checkAndProgress(
        sessionId: String,
        sessionConfig: SessionConfig? = null,
        force: Boolean = false
    ): TransactionResult {
        val session = dataManager.getSession(sessionId)
            ?: return TransactionResult.Error("Session not found: $sessionId")

        return when (TransactionStage.valueOf(session.uploadStage)) {
            TransactionStage.INIT -> {
                val config = sessionConfig ?: deserializeSessionConfig(session.sessionMetadata)
                ?: return TransactionResult.Error("No session config available for recovery")

                val folderName = session.folderName
                    ?: SimpleDateFormat("yyMMdd", Locale.getDefault()).format(Date())
                val initResult = initTransaction(sessionId, config, folderName)
                if (initResult is TransactionResult.Success) {
                    // Automatically progress to next stage if successful
                    checkAndProgress(sessionId, sessionConfig, force)
                } else {
                    initResult
                }
            }

            TransactionStage.STOP -> {
                val allUploaded = retryFailedUploads(sessionId)
                if (!allUploaded && !force) {
                    logger.warn(
                        TAG,
                        "Not all chunks uploaded for session: $sessionId. Use force=true to proceed."
                    )
                    return TransactionResult.Error(
                        "Not all audio chunks were uploaded. Use forceCommit=true to proceed with partial upload."
                    )
                }
                if (!allUploaded) {
                    logger.warn(
                        TAG,
                        "Force-committing session with partial uploads: $sessionId"
                    )
                }
                val stopResult = stopTransaction(sessionId)
                if (stopResult is TransactionResult.Success) {
                    // Continue to COMMIT
                    checkAndProgress(sessionId, sessionConfig, force)
                } else {
                    stopResult
                }
            }

            TransactionStage.COMMIT -> {
                val commitResult = commitTransaction(sessionId)
                if (commitResult is TransactionResult.Success) {
                    // Continue to ANALYZING
                    checkAndProgress(sessionId, sessionConfig, force)
                } else {
                    commitResult
                }
            }

            TransactionStage.ANALYZING -> {
                when (val pollResult = pollResult(sessionId)) {
                    is TransactionPollResult.Success -> {
                        // Mark as completed explicitly to end flow
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        TransactionResult.Success()
                    }
                    is TransactionPollResult.Failed -> TransactionResult.Error(pollResult.error)
                    is TransactionPollResult.Timeout -> TransactionResult.Error("Poll timeout")
                }
            }

            TransactionStage.COMPLETED, TransactionStage.FAILURE, TransactionStage.ERROR -> {
                TransactionResult.Success() // Already terminal
            }
        }
    }

    private fun deserializeSessionConfig(metadataJson: String?): SessionConfig? {
        if (metadataJson == null) return null
        return try {
            val request = gson.fromJson(metadataJson, InitTransactionRequest::class.java)
            SessionConfig(
                languages = request.inputLanguage ?: listOf("en-IN"),
                mode = request.mode,
                modelType = request.modelType,
                outputTemplates = request.outputFormatTemplate?.map {
                    OutputTemplate(
                        templateId = it.templateId ?: "",
                        templateType = it.type,
                        templateName = it.name
                    )
                },
                patientDetails = request.patientDetails?.let {
                    PatientDetail(
                        age = it.age,
                        biologicalSex = it.biologicalSex,
                        name = it.name,
                        patientId = it.patientId,
                        visitId = it.visitId
                    )
                },
                section = request.section,
                speciality = request.speciality
            )
        } catch (e: Exception) {
            logger.error(TAG, "Failed to deserialize session config", e)
            null
        }
    }
}

sealed class TransactionResult {
    data class Success(val folderName: String = "", val bid: String = "") : TransactionResult()
    data class Error(val message: String) : TransactionResult()
}

internal sealed class TransactionPollResult {
    data class Success(val result: ScribeResultResponse) : TransactionPollResult()
    data class Failed(val error: String) : TransactionPollResult()
    data object Timeout : TransactionPollResult()
}
