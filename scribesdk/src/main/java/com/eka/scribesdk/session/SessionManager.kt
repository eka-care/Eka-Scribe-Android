package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.EventType
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionEventName
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.error.ScribeException
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.IdGenerator
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.OutputFormatTemplate
import com.eka.scribesdk.data.remote.models.requests.PatientDetails
import com.eka.scribesdk.data.remote.models.responses.toSessionResult
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.pipeline.FullAudioResult
import com.eka.scribesdk.pipeline.Pipeline
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class SessionManager(
    private val ekaScribeConfig: EkaScribeConfig,
    private val dataManager: DataManager,
    private val pipelineFactory: Pipeline.Factory,
    private val transactionManager: TransactionManager,
    private val chunkUploader: ChunkUploader,
    private val timeProvider: TimeProvider,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "SessionManager"
        private val deferredUploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val _stateFlow = MutableStateFlow(SessionState.IDLE)
    val stateFlow: Flow<SessionState> = _stateFlow.asStateFlow()

    // Stable shared flows that survive pipeline lifecycle
    private val _voiceActivityFlow =
        MutableSharedFlow<VoiceActivityData>(replay = 1, extraBufferCapacity = 640)
    val voiceActivityFlow: Flow<VoiceActivityData> = _voiceActivityFlow.asSharedFlow()

    private val _audioQualityFlow =
        MutableSharedFlow<AudioQualityMetrics>(replay = 1, extraBufferCapacity = 160)
    val audioQualityFlow: Flow<AudioQualityMetrics> = _audioQualityFlow.asSharedFlow()

    private var activeSessionId: String? = null
    private var activeSessionConfig: SessionConfig? = null
    private var pipeline: Pipeline? = null
    private var callback: EkaScribeCallback? = null
    private var eventEmitter: SessionEventEmitter? = null
    private var sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val currentState: SessionState get() = _stateFlow.value

    fun setCallback(callback: EkaScribeCallback) {
        this.callback = callback
    }

    suspend fun start(
        sessionConfig: SessionConfig = SessionConfig(),
        onStart: (String) -> Unit = {},
        onError: (ScribeError) -> Unit = {}
    ) {
        val currentState = _stateFlow.value
        if (currentState != SessionState.IDLE) {
            if (currentState == SessionState.COMPLETED || currentState == SessionState.ERROR) {
                // Previous session ended — clean up and reset
                cleanup()
                _stateFlow.value = SessionState.IDLE
            } else {
                throw ScribeException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot start new session from state: $currentState. Stop the current session first."
                )
            }
        }

        transition(SessionState.STARTING)

        val sessionId = IdGenerator.sessionId()
        activeSessionId = sessionId
        activeSessionConfig = sessionConfig
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val emitter = SessionEventEmitter(callback, sessionId)
        eventEmitter = emitter

        emitter.emit(
            SessionEventName.SESSION_START_INITIATED,
            EventType.INFO,
            "Session start initiated"
        )

        val scope = sessionScope

        withContext(Dispatchers.IO) {
            try {
                val folderName = SimpleDateFormat("yyMMdd", Locale.getDefault()).format(Date())

                // Build the init request and serialize metadata upfront
                // so it can be included in the initial INSERT (avoids a separate UPDATE)
                val bucketName = transactionManager.bucketName
                val s3Url = "s3://$bucketName/$folderName/$sessionId"
                val initRequest = InitTransactionRequest(
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
                val metadataJson = Gson().toJson(initRequest)

                // DB CALL #1: Single INSERT with ALL pre-API data
                val session = SessionEntity(
                    sessionId = sessionId,
                    createdAt = timeProvider.nowMillis(),
                    updatedAt = timeProvider.nowMillis(),
                    state = SessionState.RECORDING.name,
                    uploadStage = TransactionStage.INIT.name,
                    folderName = folderName,
                    sessionMetadata = metadataJson
                )
                dataManager.saveSession(session)

                // Call init transaction API before starting recording
                val initResult =
                    transactionManager.initTransaction(sessionId, sessionConfig, folderName)
                if (initResult is TransactionResult.Error) {
                    logger.error(TAG, "Init transaction failed: ${initResult.message}")
                    emitter.emit(
                        SessionEventName.INIT_TRANSACTION_FAILED, EventType.ERROR,
                        "Init transaction failed: ${initResult.message}",
                        mapOf("error" to initResult.message)
                    )
                    transition(SessionState.ERROR)
                    callback?.onError(
                        ScribeError(ErrorCode.INIT_TRANSACTION_FAILED, initResult.message)
                    )
                    onError(ScribeError(ErrorCode.INIT_TRANSACTION_FAILED, initResult.message))
                    return@withContext
                }

                // Extract folderName and bid from init response
                val bid = (initResult as TransactionResult.Success).bid
                emitter.emit(
                    SessionEventName.INIT_TRANSACTION_SUCCESS, EventType.SUCCESS,
                    "Init transaction succeeded",
                    mapOf("folderName" to folderName, "bid" to bid)
                )

                // Start pipeline after init succeeds
                val newPipeline = pipelineFactory.create(
                    sessionId, folderName, bid, scope
                ) { eventName, eventType, message, metadata ->
                    emitter.emit(eventName, eventType, message, metadata)
                }
                pipeline = newPipeline

                // Forward pipeline flows to stable shared flows
                newPipeline.voiceActivityFlow?.let { flow ->
                    scope.launch { flow.collect { _voiceActivityFlow.emit(it) } }
                }
                newPipeline.audioQualityFlow?.let { flow ->
                    scope.launch { flow.collect { _audioQualityFlow.emit(it) } }
                }
                scope.launch {
                    newPipeline.audioFocusFlow.collect { hasFocus ->
                        if (!hasFocus) {
                            pause()
                        }
                        emitter.emit(
                            SessionEventName.AUDIO_FOCUS_CHANGED, EventType.INFO,
                            if (hasFocus) "Audio focus gained" else "Audio focus lost",
                            mapOf("hasFocus" to hasFocus.toString())
                        )
                        callback?.onAudioFocusChanged(hasFocus)
                    }
                }

                newPipeline.start()

                transition(SessionState.RECORDING)
                callback?.onSessionStarted(sessionId)
                emitter.emit(
                    SessionEventName.RECORDING_STARTED,
                    EventType.SUCCESS,
                    "Recording started"
                )
                logger.info(TAG, "Session started: $sessionId")
                onStart(sessionId)
            } catch (e: Exception) {
                logger.error(TAG, "Failed to start session", e)
                emitter.emit(
                    SessionEventName.SESSION_START_FAILED, EventType.ERROR,
                    "Session start failed: ${e.message}",
                    mapOf("error" to (e.message ?: "unknown"))
                )
                transition(SessionState.ERROR)
                val error = ScribeError(ErrorCode.UNKNOWN, e.message ?: "Failed to start session")
                callback?.onError(
                    error
                )
                onError(error)
                return@withContext
            }
        }
    }

    fun pause() {
        assertState(SessionState.RECORDING)
        pipeline?.pause()
        transition(SessionState.PAUSED)
        activeSessionId?.let { callback?.onSessionPaused(it) }
        eventEmitter?.emit(SessionEventName.SESSION_PAUSED, EventType.INFO, "Session paused")
        logger.info(TAG, "Session paused: $activeSessionId")
    }

    fun resume() {
        assertState(SessionState.PAUSED)
        pipeline?.resume()
        transition(SessionState.RECORDING)
        activeSessionId?.let { callback?.onSessionResumed(it) }
        eventEmitter?.emit(SessionEventName.SESSION_RESUMED, EventType.INFO, "Session resumed")
        logger.info(TAG, "Session resumed: $activeSessionId")
    }

    fun stop() {
        val currentState = _stateFlow.value
        if (currentState != SessionState.RECORDING && currentState != SessionState.PAUSED) {
            throw ScribeException(
                ErrorCode.INVALID_STATE_TRANSITION,
                "Cannot stop from state: $currentState"
            )
        }

        transition(SessionState.STOPPING)
        val sessionId = activeSessionId ?: return
        val emitter = eventEmitter

        emitter?.emit(
            SessionEventName.SESSION_STOP_INITIATED,
            EventType.INFO,
            "Session stop initiated"
        )

        sessionScope.launch(Dispatchers.IO) {
            try {
                // 1. Stop the audio pipeline
                val fullAudioResult = pipeline?.stop()

                val chunkCount = dataManager.getChunkCount(sessionId)
                callback?.onSessionStopped(sessionId, chunkCount)
                emitter?.emit(
                    SessionEventName.PIPELINE_STOPPED, EventType.SUCCESS,
                    "Pipeline stopped, chunks=$chunkCount",
                    mapOf("chunkCount" to chunkCount.toString())
                )
                logger.info(TAG, "Pipeline stopped: $sessionId, chunks=$chunkCount")

                // 2. Transition to PROCESSING for API calls
                transition(SessionState.PROCESSING)

                // 3. Retry any failed uploads — block if not all uploaded
                emitter?.emit(
                    SessionEventName.UPLOAD_RETRY_STARTED, EventType.INFO,
                    "Retrying failed chunk uploads"
                )
                val allUploaded = transactionManager.retryFailedUploads(sessionId)
                emitter?.emit(
                    SessionEventName.UPLOAD_RETRY_COMPLETED,
                    if (allUploaded) EventType.SUCCESS else EventType.ERROR,
                    if (allUploaded) "All chunks uploaded" else "Some chunks still failed after retry",
                    mapOf("allUploaded" to allUploaded.toString())
                )

                // 4. Check uploads BEFORE calling stop API to avoid server-side inconsistency
                if (!allUploaded) {
                    logger.error(
                        TAG,
                        "Not all chunks uploaded for session: $sessionId"
                    )
                    emitter?.emit(
                        SessionEventName.SESSION_FAILED, EventType.ERROR,
                        "Not all chunks uploaded",
                        mapOf("errorCode" to ErrorCode.RETRY_EXHAUSTED.name)
                    )
                    handleTransactionError(
                        sessionId,
                        ErrorCode.RETRY_EXHAUSTED,
                        "Not all audio chunks were uploaded. Use retrySession() to retry or forceCommit to proceed."
                    )
                    return@launch
                }

                // 5. Stop transaction API (only after all chunks uploaded)
                val stopResult = transactionManager.stopTransaction(sessionId)
                if (stopResult is TransactionResult.Error) {
                    logger.error(TAG, "Stop transaction failed: ${stopResult.message}")
                    emitter?.emit(
                        SessionEventName.STOP_TRANSACTION_FAILED, EventType.ERROR,
                        "Stop transaction failed: ${stopResult.message}",
                        mapOf("error" to stopResult.message)
                    )
                    handleTransactionError(
                        sessionId,
                        ErrorCode.STOP_TRANSACTION_FAILED,
                        stopResult.message
                    )
                    return@launch
                }
                emitter?.emit(
                    SessionEventName.STOP_TRANSACTION_SUCCESS, EventType.SUCCESS,
                    "Stop transaction succeeded"
                )

                // 6. Commit transaction API
                val commitResult = transactionManager.commitTransaction(sessionId)
                if (commitResult is TransactionResult.Error) {
                    logger.error(TAG, "Commit transaction failed: ${commitResult.message}")
                    emitter?.emit(
                        SessionEventName.COMMIT_TRANSACTION_FAILED, EventType.ERROR,
                        "Commit transaction failed: ${commitResult.message}",
                        mapOf("error" to commitResult.message)
                    )
                    handleTransactionError(
                        sessionId,
                        ErrorCode.COMMIT_TRANSACTION_FAILED,
                        commitResult.message
                    )
                    return@launch
                }
                emitter?.emit(
                    SessionEventName.COMMIT_TRANSACTION_SUCCESS, EventType.SUCCESS,
                    "Commit transaction succeeded"
                )

                // 6. Poll for results
                val pollResult = transactionManager.pollResult(sessionId)
                when (pollResult) {
                    is TransactionPollResult.Success -> {
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        transition(SessionState.COMPLETED)
                        val sessionResult = pollResult.result.toSessionResult(sessionId)
                        callback?.onSessionCompleted(sessionId, sessionResult)
                        emitter?.emit(
                            SessionEventName.SESSION_COMPLETED, EventType.SUCCESS,
                            "Session completed successfully"
                        )
                        logger.info(TAG, "Session completed: $sessionId")
                    }

                    is TransactionPollResult.Failed -> {
                        emitter?.emit(
                            SessionEventName.POLL_RESULT_FAILED, EventType.ERROR,
                            "Poll result failed: ${pollResult.error}",
                            mapOf("error" to pollResult.error)
                        )
                        handleTransactionError(
                            sessionId,
                            ErrorCode.TRANSCRIPTION_FAILED,
                            pollResult.error
                        )
                    }

                    is TransactionPollResult.Timeout -> {
                        // Timeout is not necessarily an error — results may arrive later
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        transition(SessionState.COMPLETED)
                        emitter?.emit(
                            SessionEventName.POLL_RESULT_TIMEOUT, EventType.ERROR,
                            "Poll timed out, session may complete later"
                        )
                        logger.warn(TAG, "Poll timeout, session may complete later: $sessionId")
                    }
                }

                // Launch deferred full audio upload (fire-and-forget, survives session cleanup)
                if (fullAudioResult != null && ekaScribeConfig.fullAudioOutput) {
                    launchDeferredFullAudioUpload(fullAudioResult)
                }
            } catch (e: Exception) {
                logger.error(TAG, "Error stopping session", e)
                emitter?.emit(
                    SessionEventName.SESSION_FAILED, EventType.ERROR,
                    "Session failed with exception: ${e.message}",
                    mapOf("error" to (e.message ?: "unknown"))
                )
                transition(SessionState.ERROR)
                callback?.onError(
                    ScribeError(ErrorCode.UNKNOWN, e.message ?: "Failed to stop session")
                )
            } finally {
                cleanup()
            }
        }
    }

    fun destroy() {
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(10_000L) {
                pipeline?.stop()
            }
        }
        cleanup()
        transition(SessionState.IDLE)
        logger.info(TAG, "SessionManager destroyed")
    }

    private fun handleTransactionError(sessionId: String, errorCode: ErrorCode, message: String) {
        transition(SessionState.ERROR)
        callback?.onSessionFailed(sessionId, ScribeError(errorCode, message))
    }

    private fun transition(newState: SessionState) {
        val current = _stateFlow.value
        if (current == newState) return

        if (!current.canTransitionTo(newState)) {
            logger.warn(TAG, "Invalid transition: $current -> $newState")
            throw ScribeException(
                ErrorCode.INVALID_STATE_TRANSITION,
                "Invalid state transition: $current -> $newState"
            )
        }

        logger.debug(TAG, "State: $current -> $newState")
        _stateFlow.value = newState
    }

    private fun assertState(expected: SessionState) {
        val current = _stateFlow.value
        if (current != expected) {
            throw ScribeException(
                ErrorCode.INVALID_STATE_TRANSITION,
                "Expected state $expected but was $current"
            )
        }
    }

    private fun cleanup() {
        pipeline = null
        activeSessionId = null
        activeSessionConfig = null
        eventEmitter = null
        sessionScope.cancel()
    }

    private fun launchDeferredFullAudioUpload(result: FullAudioResult) {
        val emitter = eventEmitter
        deferredUploadScope.launch {
            try {
                val file = File(result.filePath)
                if (!file.exists()) {
                    logger.warn(TAG, "Full audio file not found: ${result.filePath}")
                    return@launch
                }

                val metadata = UploadMetadata(
                    chunkId = "${result.sessionId}_full_audio",
                    sessionId = result.sessionId,
                    chunkIndex = -1,
                    fileName = "full_audio.m4a_",
                    folderName = result.folderName,
                    bid = result.bid
                )

                when (val uploadResult = chunkUploader.upload(file, metadata)) {
                    is UploadResult.Success -> {
                        logger.info(TAG, "Full audio uploaded & cleaned: ${result.sessionId}")
                        emitter?.emit(
                            SessionEventName.FULL_AUDIO_UPLOADED, EventType.SUCCESS,
                            "Full audio uploaded successfully"
                        )
                    }

                    is UploadResult.Failure -> {
                        logger.warn(
                            TAG,
                            "Full audio upload failed: ${result.sessionId} - ${uploadResult.error}"
                        )
                        emitter?.emit(
                            SessionEventName.FULL_AUDIO_UPLOAD_FAILED, EventType.ERROR,
                            "Full audio upload failed: ${uploadResult.error}",
                            mapOf("error" to uploadResult.error)
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error(TAG, "Deferred full audio upload failed", e)
                emitter?.emit(
                    SessionEventName.FULL_AUDIO_UPLOAD_FAILED, EventType.ERROR,
                    "Full audio upload exception: ${e.message}",
                    mapOf("error" to (e.message ?: "unknown"))
                )
            }
        }
    }
}
