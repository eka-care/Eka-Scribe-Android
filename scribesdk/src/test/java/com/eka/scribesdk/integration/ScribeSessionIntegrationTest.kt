package com.eka.scribesdk.integration

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionEvent
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.responses.InitTransactionResponse
import com.eka.scribesdk.data.remote.models.responses.ResultStatus
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.StopTransactionResponse
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.pipeline.Pipeline
import com.eka.scribesdk.session.SessionManager
import com.eka.scribesdk.session.TransactionManager
import com.eka.scribesdk.session.TransactionResult
import com.haroldadmin.cnradapter.NetworkResponse
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Integration tests that simulate 8 complete session lifecycles by wiring
 * SessionManager with a real TransactionManager (backed by faked API/storage)
 * and a MockK'd Pipeline/Pipeline.Factory.
 *
 * Scenarios:
 * 1. Happy path (start → record → stop → all uploaded → stop/commit/poll → COMPLETED)
 * 2. Init transaction failure → ERROR
 * 3. Upload failure with retry → succeeds on retry → COMPLETED
 * 4. Force commit with partial uploads → checkAndProgress(force=true) → success
 * 5. Pause/resume flow → normal completion
 * 6. Poll timeout → COMPLETED (not ERROR)
 * 7. Poll failure (FAILURE status) → ERROR
 * 8. Audio focus loss → auto-pause with callback → client resumes
 */
internal class ScribeSessionIntegrationTest {

    companion object {
        private const val BUCKET_NAME = "test-bucket"
        private const val MAX_RETRIES = 2
    }

    private lateinit var fakeApi: FakeApiService
    private lateinit var fakeDm: FakeDataManager
    private lateinit var fakeUploader: FakeChunkUploader
    private lateinit var callbackRecorder: CallbackRecorder
    private lateinit var mockPipeline: Pipeline
    private lateinit var mockPipelineFactory: Pipeline.Factory
    private lateinit var audioFocusFlow: MutableSharedFlow<Boolean>

    @Before
    fun setUp() {
        fakeApi = FakeApiService()
        fakeDm = FakeDataManager()
        fakeUploader = FakeChunkUploader()
        callbackRecorder = CallbackRecorder()

        audioFocusFlow = MutableSharedFlow(replay = 1, extraBufferCapacity = 4)

        mockPipeline = mockk(relaxed = true)
        every { mockPipeline.voiceActivityFlow } returns null
        every { mockPipeline.audioQualityFlow } returns null
        every { mockPipeline.audioFocusFlow } returns audioFocusFlow
        coEvery { mockPipeline.stop() } returns null

        mockPipelineFactory = mockk()
        every {
            mockPipelineFactory.create(any(), any(), any(), any(), any())
        } returns mockPipeline
    }

    private fun createSessionManager(): SessionManager {
        val tm = TransactionManager(
            apiService = fakeApi,
            dataManager = fakeDm,
            chunkUploader = fakeUploader,
            bucketName = BUCKET_NAME,
            maxUploadRetries = MAX_RETRIES,
            logger = NoOpLogger()
        )
        val manager = SessionManager(
            dataManager = fakeDm,
            pipelineFactory = mockPipelineFactory,
            transactionManager = tm,
            chunkUploader = fakeUploader,
            timeProvider = FakeTimeProvider(),
            logger = NoOpLogger()
        )
        manager.setCallback(callbackRecorder)
        return manager
    }

    // =====================================================================
    // RESPONSE HELPERS
    // =====================================================================

    private fun initOk(bid: String = "bid-001") = InitTransactionResponse(
        bId = bid, message = "ok", status = "success", txnId = null, error = null
    )

    private fun stopOk() = StopTransactionResponse(
        message = "ok", status = "success", error = null
    )

    private fun <S, E> netSuccess(body: S, code: Int = 200): NetworkResponse<S, E> =
        NetworkResponse.Success(body, okResponse(body, code))

    private fun makeResultResponse(status: ResultStatus): ScribeResultResponse {
        val output = ScribeResultResponse.Data.Output(
            errors = null, name = "output", status = status,
            templateId = null, type = null, value = "result", warnings = null
        )
        return ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null, createdAt = null,
                output = listOf(output), templateResults = null
            )
        )
    }

    // =====================================================================
    // SCENARIO 1: HAPPY PATH
    // =====================================================================

    @Test
    fun `scenario 1 - happy path - start record stop commit poll success`() {
        fakeApi.initResponse = netSuccess(initOk("bid-happy"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        fakeDm.allChunksUploaded = true

        val manager = createSessionManager()
        val sessionId = kotlinx.coroutines.runBlocking { manager.start() }
        assertNotNull(sessionId)

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        assertEquals(SessionState.RECORDING, manager.currentState)
        assertTrue(
            "onSessionStarted should be called",
            callbackRecorder.startedIds.contains(sessionId)
        )

        manager.stop()
        waitFor(5000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }

        assertEquals(SessionState.COMPLETED, manager.currentState)
        assertTrue(
            "onSessionStopped should be called",
            callbackRecorder.stoppedIds.contains(sessionId)
        )
        assertTrue(
            "onSessionCompleted should be called",
            callbackRecorder.completedIds.contains(sessionId)
        )
    }

    // =====================================================================
    // SCENARIO 2: INIT TRANSACTION FAILURE
    // =====================================================================

    @Test
    fun `scenario 2 - init failure - session transitions to ERROR`() {
        fakeApi.initResponse = NetworkResponse.NetworkError(IOException("Connection refused"))

        val manager = createSessionManager()
        val sessionId = kotlinx.coroutines.runBlocking { manager.start() }
        assertNotNull(sessionId)

        waitFor(2000) { manager.currentState == SessionState.ERROR }
        assertEquals(SessionState.ERROR, manager.currentState)
        assertTrue("onError should be called", callbackRecorder.errors.isNotEmpty())
        assertEquals(ErrorCode.INIT_TRANSACTION_FAILED, callbackRecorder.errors[0].code)
    }

    // =====================================================================
    // SCENARIO 3: UPLOAD FAILURE + RETRY
    // =====================================================================

    @Test
    fun `scenario 3 - upload failure with retry succeeds on second try`() {
        fakeApi.initResponse = netSuccess(initOk("bid-retry"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )

        // Provide a failed chunk that the retry loop will re-upload.
        val tempFile = File.createTempFile("test_chunk", ".m4a")
        tempFile.deleteOnExit()
        fakeDm.failedChunksList = listOf(
            AudioChunkEntity(
                chunkId = "c-fail", sessionId = "any",
                chunkIndex = 0, filePath = tempFile.absolutePath,
                fileName = "1.m4a", startTimeMs = 0, endTimeMs = 10000,
                durationMs = 10000, createdAt = 1000L
            )
        )
        // After retry completes, areAllChunksUploaded returns true
        fakeDm.allChunksUploaded = true
        fakeUploader.result = UploadResult.Success("s3://ok")

        val manager = createSessionManager()
        kotlinx.coroutines.runBlocking { manager.start() }

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        manager.stop()

        waitFor(5000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }

        assertEquals(SessionState.COMPLETED, manager.currentState)
        assertTrue("Uploader should have been called for retry", fakeUploader.uploadCount > 0)
    }

    // =====================================================================
    // SCENARIO 4: FORCE COMMIT WITH PARTIAL UPLOADS
    // =====================================================================

    @Test
    fun `scenario 4 - checkAndProgress force commit with partial uploads`() {
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        fakeDm.sessionEntity = SessionEntity(
            sessionId = "force-session", createdAt = 1000L, updatedAt = 1000L,
            state = "RECORDING", uploadStage = TransactionStage.STOP.name,
            folderName = "260302", bid = "bid-force"
        )
        fakeDm.allChunksUploaded = false
        fakeDm.failedChunksList = emptyList()

        val tm = TransactionManager(
            apiService = fakeApi,
            dataManager = fakeDm,
            chunkUploader = fakeUploader,
            bucketName = BUCKET_NAME,
            maxUploadRetries = MAX_RETRIES,
            logger = NoOpLogger()
        )

        // Without force → error
        val resultNoForce = kotlinx.coroutines.runBlocking {
            tm.checkAndProgress("force-session", force = false)
        }
        assertTrue("Should fail without force", resultNoForce is TransactionResult.Error)
        assertTrue(
            (resultNoForce as TransactionResult.Error).message.contains("forceCommit")
        )

        // With force → success (calls stopTransaction)
        val resultForce = kotlinx.coroutines.runBlocking {
            tm.checkAndProgress("force-session", force = true)
        }
        assertTrue("Should succeed with force", resultForce is TransactionResult.Success)
        assertTrue("Stop API should be called", fakeApi.stopCalled)
    }

    // =====================================================================
    // SCENARIO 5: PAUSE / RESUME FLOW
    // =====================================================================

    @Test
    fun `scenario 5 - pause resume flow completes normally`() {
        fakeApi.initResponse = netSuccess(initOk("bid-pause"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        fakeDm.allChunksUploaded = true

        val manager = createSessionManager()
        val sessionId = kotlinx.coroutines.runBlocking { manager.start() }

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        assertEquals(SessionState.RECORDING, manager.currentState)

        // Pause
        manager.pause()
        assertEquals(SessionState.PAUSED, manager.currentState)
        assertTrue("onSessionPaused called", callbackRecorder.pausedIds.contains(sessionId))

        // Resume
        manager.resume()
        assertEquals(SessionState.RECORDING, manager.currentState)
        assertTrue("onSessionResumed called", callbackRecorder.resumedIds.contains(sessionId))

        // Stop
        manager.stop()
        waitFor(5000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }

        assertEquals(SessionState.COMPLETED, manager.currentState)
    }

    // =====================================================================
    // SCENARIO 6: POLL TIMEOUT
    // =====================================================================

    @Test
    fun `scenario 6 - poll timeout results in COMPLETED state`() {
        fakeApi.initResponse = netSuccess(initOk("bid-timeout"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())

        // All poll responses return 202 (still processing) — always return same response
        val processing = ScribeResultResponse(data = null)
        fakeApi.pollResponses = MutableList(10) {
            netSuccess(processing, 202)
        }
        fakeDm.allChunksUploaded = true

        val manager = createSessionManager()
        kotlinx.coroutines.runBlocking { manager.start() }

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        manager.stop()

        // Poll timeout leads to COMPLETED (not ERROR) per SessionManager.stop() logic
        waitFor(30000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }

        assertEquals(
            "Poll timeout should result in COMPLETED",
            SessionState.COMPLETED,
            manager.currentState
        )
    }

    // =====================================================================
    // SCENARIO 7: POLL FAILURE
    // =====================================================================

    @Test
    fun `scenario 7 - poll failure with FAILURE status goes to ERROR`() {
        fakeApi.initResponse = netSuccess(initOk("bid-failure"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.FAILURE))
        )
        fakeDm.allChunksUploaded = true

        val manager = createSessionManager()
        kotlinx.coroutines.runBlocking { manager.start() }

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        manager.stop()

        waitFor(5000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }

        assertEquals(SessionState.ERROR, manager.currentState)
        assertTrue(
            "onSessionFailed should be called",
            callbackRecorder.failedErrors.isNotEmpty()
        )
        assertEquals(ErrorCode.TRANSCRIPTION_FAILED, callbackRecorder.failedErrors[0].code)
    }

    // =====================================================================
    // SCENARIO 8: AUDIO FOCUS LOSS AUTO-PAUSE
    // =====================================================================

    @Test
    fun `scenario 8 - audio focus loss auto-pauses then client resumes and completes`() {
        fakeApi.initResponse = netSuccess(initOk("bid-focus"))
        fakeApi.stopResponse = netSuccess(stopOk())
        fakeApi.commitResponse = netSuccess(stopOk())
        fakeApi.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        fakeDm.allChunksUploaded = true

        val manager = createSessionManager()
        val sessionId = kotlinx.coroutines.runBlocking { manager.start() }

        waitFor(2000) { manager.currentState == SessionState.RECORDING }
        assertEquals(SessionState.RECORDING, manager.currentState)

        // Simulate audio focus loss via the audioFocusFlow
        audioFocusFlow.tryEmit(false)

        // Wait for SessionManager's collector to auto-pause
        waitFor(1000) { manager.currentState == SessionState.PAUSED }
        assertEquals(
            "Audio focus loss should auto-pause the session",
            SessionState.PAUSED,
            manager.currentState
        )
        assertTrue(
            "onSessionPaused should be called on focus loss",
            callbackRecorder.pausedIds.contains(sessionId)
        )
        assertTrue(
            "onAudioFocusChanged(false) should be called",
            callbackRecorder.focusChanges.contains(false)
        )

        // Client resumes the session
        manager.resume()
        assertEquals(SessionState.RECORDING, manager.currentState)
        assertTrue(
            "onSessionResumed should be called after resume",
            callbackRecorder.resumedIds.contains(sessionId)
        )

        // Stop and complete normally
        manager.stop()
        waitFor(5000) {
            manager.currentState == SessionState.COMPLETED || manager.currentState == SessionState.ERROR
        }
        assertEquals(SessionState.COMPLETED, manager.currentState)
    }

    // =====================================================================
    // UTILITIES
    // =====================================================================

    private fun waitFor(maxMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < maxMs) {
            Thread.sleep(50)
        }
    }

    private fun <T> okResponse(body: T, code: Int): retrofit2.Response<T> {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .message("OK")
            .request(okhttp3.Request.Builder().url("http://test").build())
            .build()
        return retrofit2.Response.success(body, raw)
    }

    // =====================================================================
    // CALLBACK RECORDER
    // =====================================================================

    private class CallbackRecorder : EkaScribeCallback {
        val startedIds = mutableListOf<String>()
        val pausedIds = mutableListOf<String>()
        val resumedIds = mutableListOf<String>()
        val stoppedIds = mutableListOf<String>()
        val completedIds = mutableListOf<String>()
        val errors = mutableListOf<ScribeError>()
        val failedErrors = mutableListOf<ScribeError>()
        val focusChanges = mutableListOf<Boolean>()
        val events = mutableListOf<SessionEvent>()

        override fun onSessionStarted(sessionId: String) {
            startedIds.add(sessionId)
        }

        override fun onSessionPaused(sessionId: String) {
            pausedIds.add(sessionId)
        }

        override fun onSessionResumed(sessionId: String) {
            resumedIds.add(sessionId)
        }

        override fun onSessionStopped(sessionId: String, chunkCount: Int) {
            stoppedIds.add(sessionId)
        }

        override fun onError(error: ScribeError) {
            errors.add(error)
        }

        override fun onSessionCompleted(sessionId: String, result: SessionResult) {
            completedIds.add(sessionId)
        }

        override fun onSessionFailed(sessionId: String, error: ScribeError) {
            failedErrors.add(error)
        }

        override fun onAudioFocusChanged(hasFocus: Boolean) {
            focusChanges.add(hasFocus)
        }

        override fun onSessionEvent(event: SessionEvent) {
            events.add(event)
        }
    }

    // =====================================================================
    // FAKES
    // =====================================================================

    internal class FakeDataManager : DataManager {
        val uploadStages = mutableMapOf<String, String>()
        var sessionEntity: SessionEntity? = null
        var failedChunksList: List<AudioChunkEntity> = emptyList()
        var retryExhaustedChunksList: List<AudioChunkEntity> = emptyList()
        var allChunksUploaded = true
        var allChunksUploadedSequence: MutableList<Boolean>? = null
        private var allChunksCheckCount = 0
        private val sessions = mutableMapOf<String, SessionEntity>()

        override suspend fun saveSession(session: SessionEntity) {
            sessions[session.sessionId] = session
        }

        override suspend fun saveChunk(chunk: AudioChunkEntity) {}
        override suspend fun getSession(sessionId: String): SessionEntity? =
            sessionEntity ?: sessions[sessionId]

        override suspend fun updateSessionState(sessionId: String, state: String) {
            sessions[sessionId] = sessions[sessionId]?.copy(state = state)
                ?: SessionEntity(sessionId, 0, 0, state)
        }

        override suspend fun updateChunkCount(sessionId: String, count: Int) {}
        override suspend fun getPendingChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun markInProgress(chunkId: String) {}
        override suspend fun markUploaded(chunkId: String) {}
        override suspend fun markFailed(chunkId: String) {}
        override suspend fun getChunkCount(sessionId: String) = 0
        override fun sessionFlow(sessionId: String): Flow<SessionEntity?> = emptyFlow()
        override suspend fun deleteSession(sessionId: String) {}
        override suspend fun updateUploadStage(sessionId: String, stage: String) {
            uploadStages[sessionId] = stage
            sessionEntity = sessionEntity?.copy(uploadStage = stage)
            sessions[sessionId] = sessions[sessionId]?.copy(uploadStage = stage)
                ?: SessionEntity(sessionId, 0L, 0L, "RECORDING", uploadStage = stage)
        }

        override suspend fun updateSessionMetadata(sessionId: String, metadata: String) {}
        override suspend fun getFailedChunks(sessionId: String, maxRetries: Int) = failedChunksList
        override suspend fun getUploadedChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun areAllChunksUploaded(sessionId: String): Boolean {
            val seq = allChunksUploadedSequence
            if (seq != null && seq.isNotEmpty()) {
                val idx = allChunksCheckCount.coerceAtMost(seq.size - 1)
                allChunksCheckCount++
                return seq[idx]
            }
            return allChunksUploaded
        }

        override suspend fun getSessionsByStage(stage: String) = emptyList<SessionEntity>()
        override suspend fun getAllChunks(sessionId: String) =
            failedChunksList + retryExhaustedChunksList
        override suspend fun updateFolderAndBid(
            sessionId: String,
            folderName: String,
            bid: String
        ) {
        }

        override suspend fun getAllSessions() = emptyList<SessionEntity>()
        override suspend fun getRetryExhaustedChunks(sessionId: String, maxRetries: Int) =
            retryExhaustedChunksList

        override suspend fun resetRetryCount(chunkId: String) {}
    }

    internal class FakeChunkUploader(
        var result: UploadResult = UploadResult.Success("s3://test/ok.m4a")
    ) : ChunkUploader {
        var uploadCount = 0
        override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
            uploadCount++
            return result
        }
    }

    internal class FakeApiService : ScribeApiService {
        var initResponse: NetworkResponse<InitTransactionResponse, InitTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var stopResponse: NetworkResponse<StopTransactionResponse, StopTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var commitResponse: NetworkResponse<StopTransactionResponse, StopTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var pollResponses: MutableList<NetworkResponse<ScribeResultResponse, ScribeResultResponse>> =
            mutableListOf()

        var initCalled = false
        var stopCalled = false
        var commitCalled = false
        var pollCallCount = 0

        override suspend fun initTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
        ): NetworkResponse<InitTransactionResponse, InitTransactionResponse> {
            initCalled = true
            return initResponse
        }

        override suspend fun stopTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
        ): NetworkResponse<StopTransactionResponse, StopTransactionResponse> {
            stopCalled = true
            return stopResponse
        }

        override suspend fun commitTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
        ): NetworkResponse<StopTransactionResponse, StopTransactionResponse> {
            commitCalled = true
            return commitResponse
        }

        override suspend fun getTransactionResult(
            sessionId: String
        ): NetworkResponse<ScribeResultResponse, ScribeResultResponse> {
            val idx = pollCallCount.coerceAtMost(pollResponses.size - 1)
            pollCallCount++
            return if (pollResponses.isNotEmpty()) pollResponses[idx]
            else NetworkResponse.NetworkError(IOException("No poll configured"))
        }

        override suspend fun getS3Config(url: String) = throw NotImplementedError()
        override suspend fun convertTransactionResult(sessionId: String, templateId: String) =
            throw NotImplementedError()

        override suspend fun updateSessionOutput(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
        ) = throw NotImplementedError()

        override suspend fun getTemplates() = throw NotImplementedError()
        override suspend fun updateTemplates(requestBody: com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest) =
            throw NotImplementedError()

        override suspend fun getUserConfig() = throw NotImplementedError()
        override suspend fun updateUserConfig(request: com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest) =
            throw NotImplementedError()

        override suspend fun getHistory(queries: Map<String, String>) = throw NotImplementedError()
    }

    internal class FakeTimeProvider : TimeProvider {
        override fun nowMillis() = System.currentTimeMillis()
    }

    internal class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
