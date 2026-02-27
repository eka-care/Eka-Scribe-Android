package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.AudioQualityMetrics
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionConfig
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
import com.eka.scribesdk.data.remote.models.responses.toSessionResult
import com.eka.scribesdk.pipeline.Pipeline
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
import kotlinx.coroutines.withTimeoutOrNull

internal class SessionManager(
    private val config: EkaScribeConfig,
    private val dataManager: DataManager,
    private val pipelineFactory: Pipeline.Factory,
    private val transactionManager: TransactionManager,
    private val timeProvider: TimeProvider,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "SessionManager"
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
    private var sessionScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val currentState: SessionState get() = _stateFlow.value

    fun setCallback(callback: EkaScribeCallback) {
        this.callback = callback
    }

    fun start(sessionConfig: SessionConfig = SessionConfig()): String {
        assertState(SessionState.IDLE)

        transition(SessionState.STARTING)

        val sessionId = IdGenerator.sessionId()
        activeSessionId = sessionId
        activeSessionConfig = sessionConfig
        sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val scope = sessionScope

        scope.launch(Dispatchers.IO) {
            try {
                val session = SessionEntity(
                    sessionId = sessionId,
                    createdAt = timeProvider.nowMillis(),
                    updatedAt = timeProvider.nowMillis(),
                    state = SessionState.RECORDING.name,
                    uploadStage = TransactionStage.INIT.name
                )
                dataManager.saveSession(session)

                // Call init transaction API before starting recording
                val initResult = transactionManager.initTransaction(sessionId, sessionConfig)
                if (initResult is TransactionResult.Error) {
                    logger.error(TAG, "Init transaction failed: ${initResult.message}")
                    transition(SessionState.ERROR)
                    callback?.onError(
                        ScribeError(ErrorCode.INIT_TRANSACTION_FAILED, initResult.message)
                    )
                    return@launch
                }

                // Extract folderName and bid from init response
                val folderName = (initResult as TransactionResult.Success).folderName
                val bid = initResult.bid

                // Start pipeline after init succeeds
                val newPipeline = pipelineFactory.create(sessionId, folderName, bid, scope)
                pipeline = newPipeline

                // Forward pipeline flows to stable shared flows
                newPipeline.voiceActivityFlow?.let { flow ->
                    scope.launch { flow.collect { _voiceActivityFlow.emit(it) } }
                }
                newPipeline.audioQualityFlow?.let { flow ->
                    scope.launch { flow.collect { _audioQualityFlow.emit(it) } }
                }

                newPipeline.start()

                transition(SessionState.RECORDING)
                callback?.onSessionStarted(sessionId)
                logger.info(TAG, "Session started: $sessionId")
            } catch (e: Exception) {
                logger.error(TAG, "Failed to start session", e)
                transition(SessionState.ERROR)
                callback?.onError(
                    ScribeError(ErrorCode.UNKNOWN, e.message ?: "Failed to start session")
                )
            }
        }

        return sessionId
    }

    fun pause() {
        assertState(SessionState.RECORDING)
        pipeline?.pause()
        transition(SessionState.PAUSED)
        activeSessionId?.let { callback?.onSessionPaused(it) }
        logger.info(TAG, "Session paused: $activeSessionId")
    }

    fun resume() {
        assertState(SessionState.PAUSED)
        pipeline?.resume()
        transition(SessionState.RECORDING)
        activeSessionId?.let { callback?.onSessionResumed(it) }
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

        sessionScope?.launch(Dispatchers.IO) {
            try {
                // 1. Stop the audio pipeline
                pipeline?.stop()

                val chunkCount = dataManager.getChunkCount(sessionId)
                callback?.onSessionStopped(sessionId, chunkCount)
                logger.info(TAG, "Pipeline stopped: $sessionId, chunks=$chunkCount")

                // 2. Transition to PROCESSING for API calls
                transition(SessionState.PROCESSING)

                // 3. Retry any failed uploads — block if not all uploaded
                val allUploaded = transactionManager.retryFailedUploads(sessionId)

                // 4. Stop transaction API
                val stopResult = transactionManager.stopTransaction(sessionId)
                if (stopResult is TransactionResult.Error) {
                    logger.error(TAG, "Stop transaction failed: ${stopResult.message}")
                    handleTransactionError(
                        sessionId,
                        ErrorCode.STOP_TRANSACTION_FAILED,
                        stopResult.message
                    )
                    return@launch
                }

                if (!allUploaded) {
                    logger.error(
                        TAG,
                        "Not all chunks uploaded for session: $sessionId"
                    )
                    handleTransactionError(
                        sessionId,
                        ErrorCode.RETRY_EXHAUSTED,
                        "Not all audio chunks were uploaded. Use retrySession() to retry or forceCommit to proceed."
                    )
                    return@launch
                }

                // 5. Commit transaction API
                val commitResult = transactionManager.commitTransaction(sessionId)
                if (commitResult is TransactionResult.Error) {
                    logger.error(TAG, "Commit transaction failed: ${commitResult.message}")
                    handleTransactionError(
                        sessionId,
                        ErrorCode.COMMIT_TRANSACTION_FAILED,
                        commitResult.message
                    )
                    return@launch
                }

                // 6. Poll for results
                val pollResult = transactionManager.pollResult(sessionId)
                when (pollResult) {
                    is TransactionPollResult.Success -> {
                        dataManager.updateSessionState(sessionId, SessionState.COMPLETED.name)
                        transition(SessionState.COMPLETED)
                        val sessionResult = pollResult.result.toSessionResult(sessionId)
                        callback?.onSessionCompleted(sessionId, sessionResult)
                        logger.info(TAG, "Session completed: $sessionId")
                    }

                    is TransactionPollResult.Failed -> {
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
                        logger.warn(TAG, "Poll timeout, session may complete later: $sessionId")
                    }
                }
            } catch (e: Exception) {
                logger.error(TAG, "Error stopping session", e)
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
        sessionScope.cancel()
    }
}
