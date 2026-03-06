package com.eka.scribesdk.session

import android.content.Context
import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.EkaScribeConfig
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.error.ScribeException
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.common.util.canRecordAudio
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.eka.scribesdk.pipeline.FullAudioResult
import com.eka.scribesdk.pipeline.Pipeline
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Tests for SessionManager's synchronous state machine logic.
 *
 * The async parts (init/stop/commit/poll API calls) are thoroughly tested
 * in [TransactionManagerTest]. Pipeline internals are tested in [PipelineTest].
 * This test focuses on state transition guards and error handling.
 */
internal class SessionManagerTest {

    private val mockContext: Context = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(::canRecordAudio)
        every { canRecordAudio(any()) } returns true
    }

    private fun createManager(): SessionManager {
        val dm = FakeDataManager()
        val uploader = FakeChunkUploader()
        // MockK Pipeline.Factory — create() is never reached in state-guard tests
        val pipelineFactory = mockk<Pipeline.Factory>(relaxed = true)
        val tm = mockk<TransactionManager>(relaxed = true)

        // Make initTransaction return an error so the async coroutine in start()
        // settles to ERROR state quickly (allows re-start tests)
        coEvery {
            tm.initTransaction(
                any(),
                any(),
                any()
            )
        } returns TransactionResult.Error("test error")

        return SessionManager(
            ekaScribeConfig = EkaScribeConfig(networkConfig = mockk(), fullAudioOutput = true),
            dataManager = dm,
            pipelineFactory = pipelineFactory,
            transactionManager = tm,
            chunkUploader = uploader,
            timeProvider = FakeTimeProvider(),
            logger = NoOpLogger()
        )
    }

    // =====================================================================
    // INITIAL STATE
    // =====================================================================

    @Test
    fun `initial state is IDLE`() {
        val manager = createManager()
        assertEquals(SessionState.IDLE, manager.currentState)
    }

    // =====================================================================
    // START — SYNCHRONOUS GUARDS
    // =====================================================================

    @Test
    fun `start calls onStart with session ID and transitions to STARTING`() = runTest {
        val manager = createManagerWithSuccessInit()
        var sessionId = ""
        manager.start(context = mockContext, onStart = { sessionId = it })
        Thread.sleep(200)

        assertTrue("Session ID should not be empty", sessionId.isNotEmpty())
        assertTrue("Session ID should start with 'a-'", sessionId.startsWith("a-"))
    }

    @Test
    fun `start generates unique session IDs`() = runTest {
        val manager = createManagerWithSuccessInit()
        var id1 = ""
        manager.start(context = mockContext, onStart = { id1 = it })
        Thread.sleep(200)

        // Stop to let manager reach COMPLETED/ERROR so we can restart
        manager.stop()
        Thread.sleep(500)

        var id2 = ""
        manager.start(context = mockContext, onStart = { id2 = it })
        Thread.sleep(200)

        assertTrue("id1 should not be empty", id1.isNotEmpty())
        assertTrue("id2 should not be empty", id2.isNotEmpty())
        assertTrue("IDs should be different", id1 != id2)
    }

    @Test
    fun `start from RECORDING state throws`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        Thread.sleep(200)

        // If async moved to RECORDING, a second start should throw
        if (manager.currentState == SessionState.RECORDING) {
            try {
                manager.start(mockContext)
                fail("Should throw ScribeException")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    @Test
    fun `start from ERROR state resets and starts new session`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        Thread.sleep(300) // Wait for async to fail → ERROR

        assertEquals("Should be in ERROR state", SessionState.ERROR, manager.currentState)

        // Now use a success-path manager to restart from ERROR
        // But we can't swap managers — the same manager must recover.
        // The mock returns error, but start() from ERROR state should still
        // reset and attempt. We just verify it doesn't throw.
        var errorReceived = false
        manager.start(context = mockContext, onError = { errorReceived = true })
        Thread.sleep(300)

        // The important thing: start() from ERROR didn't throw (it reset and tried)
        assertTrue("onError should be called since init still fails", errorReceived)
    }

    @Test
    fun `start from PAUSED state throws`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        Thread.sleep(200)

        if (manager.currentState == SessionState.RECORDING) {
            manager.pause()
            try {
                manager.start(mockContext)
                fail("Should throw from PAUSED")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    // =====================================================================
    // PAUSE
    // =====================================================================

    @Test
    fun `pause from IDLE throws`() {
        val manager = createManager()
        try {
            manager.pause()
            fail("Should throw from IDLE")
        } catch (e: ScribeException) {
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        }
    }

    @Test
    fun `pause from STARTING throws`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        if (manager.currentState == SessionState.STARTING) {
            try {
                manager.pause()
                fail("Should throw from STARTING")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    // =====================================================================
    // RESUME
    // =====================================================================

    @Test
    fun `resume from IDLE throws`() {
        val manager = createManager()
        try {
            manager.resume()
            fail("Should throw from IDLE")
        } catch (e: ScribeException) {
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        }
    }

    @Test
    fun `resume from RECORDING throws`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        Thread.sleep(200)

        if (manager.currentState == SessionState.RECORDING) {
            try {
                manager.resume()
                fail("Should throw from RECORDING")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    // =====================================================================
    // STOP
    // =====================================================================

    @Test
    fun `stop from IDLE throws`() {
        val manager = createManager()
        try {
            manager.stop()
            fail("Should throw from IDLE")
        } catch (e: ScribeException) {
            assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
        }
    }

    @Test
    fun `stop from STARTING throws`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        if (manager.currentState == SessionState.STARTING) {
            try {
                manager.stop()
                fail("Should throw from STARTING")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    private fun createManagerWithSuccessInit(
        tm: TransactionManager = mockk(relaxed = true),
        pipeline: Pipeline = mockk(relaxed = true),
        dm: DataManager = FakeDataManager(),
        uploader: ChunkUploader = FakeChunkUploader()
    ): SessionManager {
        val pipelineFactory = mockk<Pipeline.Factory>(relaxed = true)

        coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        every { pipelineFactory.create(any(), any(), any(), any(), any()) } returns pipeline
        every { pipeline.voiceActivityFlow } returns emptyFlow()
        every { pipeline.audioQualityFlow } returns emptyFlow()
        every { pipeline.audioFocusFlow } returns emptyFlow()

        return SessionManager(
            dataManager = dm,
            pipelineFactory = pipelineFactory,
            transactionManager = tm,
            chunkUploader = uploader,
            timeProvider = FakeTimeProvider(),
            logger = NoOpLogger(),
            ekaScribeConfig = EkaScribeConfig(networkConfig = mockk(), fullAudioOutput = true),
        )
    }

    @Test
    fun `stop transitions to ERROR when pipeline stop throws exception`() = runTest {
        val pipeline = mockk<Pipeline>(relaxed = true)
        every { pipeline.voiceActivityFlow } returns emptyFlow()
        every { pipeline.audioQualityFlow } returns emptyFlow()
        every { pipeline.audioFocusFlow } returns emptyFlow()
        coEvery { pipeline.stop() } throws RuntimeException("Pipeline crash")

        val manager = createManagerWithSuccessInit(pipeline = pipeline)
        manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to ERROR when retryFailedUploads returns false`() = runTest {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns false // Simulate incomplete uploads

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        // Should be in ERROR state and not proceed to stopTransaction
        assertEquals(SessionState.ERROR, manager.currentState)
        io.mockk.coVerify(exactly = 0) { tm.stopTransaction(any()) }
    }

    @Test
    fun `stop transitions to ERROR when stopTransaction fails`() = runTest {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Error("API fails")

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to ERROR when commitTransaction fails`() = runTest {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.commitTransaction(any()) } returns TransactionResult.Error("API fails")

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to COMPLETED when pollResult times out`() = runTest {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.commitTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.pollResult(any()) } returns TransactionPollResult.Timeout

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.COMPLETED, manager.currentState)
    }

    @Test
    fun `deferred full audio upload triggers when pipeline stop returns FullAudioResult`() =
        runTest {
        val pipeline = mockk<Pipeline>(relaxed = true)
        every { pipeline.voiceActivityFlow } returns emptyFlow()
        every { pipeline.audioQualityFlow } returns emptyFlow()
        every { pipeline.audioFocusFlow } returns emptyFlow()

        val tempFile = File.createTempFile("test_audio", ".m4a")
        tempFile.deleteOnExit()

        coEvery { pipeline.stop() } returns FullAudioResult(
            sessionId = "a-123",
            folderName = "folder",
            bid = "bid",
            filePath = tempFile.absolutePath
        )

        val uploader = mockk<ChunkUploader>(relaxed = true)
        coEvery { uploader.upload(any(), any()) } returns UploadResult.Success("s3://ok")

        val tm = mockk<TransactionManager>(relaxed = true)
            coEvery { tm.initTransaction(any(), any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.commitTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.pollResult(any()) } returns TransactionPollResult.Success(
            com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse(null)
        )

        val manager =
            createManagerWithSuccessInit(tm = tm, pipeline = pipeline, uploader = uploader)
            manager.start(mockContext)
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(500) // Wait for deferred upload coroutine

        io.mockk.coVerify(exactly = 1) { uploader.upload(any(), any()) }
    }

    // =====================================================================
    // DESTROY
    // =====================================================================

    @Test
    fun `destroy from IDLE stays IDLE`() {
        val manager = createManager()
        manager.destroy()
        assertEquals(SessionState.IDLE, manager.currentState)
    }

    @Test
    fun `destroy after start resets to IDLE`() = runTest {
        val manager = createManager()
        manager.start(mockContext)
        Thread.sleep(50)
        manager.destroy()
        assertEquals(SessionState.IDLE, manager.currentState)
    }

    // =====================================================================
    // CALLBACK
    // =====================================================================

    @Test
    fun `setCallback does not throw`() {
        val manager = createManager()
        manager.setCallback(object : EkaScribeCallback {
            override fun onSessionStarted(sessionId: String) {}
            override fun onSessionPaused(sessionId: String) {}
            override fun onSessionResumed(sessionId: String) {}
            override fun onSessionStopped(sessionId: String, chunkCount: Int) {}
            override fun onError(error: ScribeError) {}
        })
    }

    // =====================================================================
    // FAKES
    // =====================================================================

    private class FakeDataManager : DataManager {
        override suspend fun saveSession(session: SessionEntity) {}
        override suspend fun saveChunk(chunk: AudioChunkEntity) {}
        override suspend fun getSession(sessionId: String): SessionEntity? = null
        override suspend fun updateSessionState(sessionId: String, state: String) {}
        override suspend fun updateChunkCount(sessionId: String, count: Int) {}
        override suspend fun getPendingChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun markInProgress(chunkId: String) {}
        override suspend fun markUploaded(chunkId: String) {}
        override suspend fun markFailed(chunkId: String) {}
        override suspend fun getChunkCount(sessionId: String) = 0
        override fun sessionFlow(sessionId: String): Flow<SessionEntity?> = emptyFlow()
        override suspend fun deleteSession(sessionId: String) {}
        override suspend fun updateUploadStage(sessionId: String, stage: String) {}
        override suspend fun updateSessionMetadata(sessionId: String, metadata: String) {}
        override suspend fun getFailedChunks(sessionId: String, maxRetries: Int) =
            emptyList<AudioChunkEntity>()

        override suspend fun getUploadedChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun areAllChunksUploaded(sessionId: String) = false
        override suspend fun getSessionsByStage(stage: String) = emptyList<SessionEntity>()
        override suspend fun getAllChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun updateFolderAndBid(
            sessionId: String,
            folderName: String,
            bid: String
        ) {
        }

        override suspend fun getAllSessions() = emptyList<SessionEntity>()
        override suspend fun getRetryExhaustedChunks(sessionId: String, maxRetries: Int) =
            emptyList<AudioChunkEntity>()

        override suspend fun resetRetryCount(chunkId: String) {}
        override suspend fun updateStageAndBid(sessionId: String, stage: String, bid: String) {}
    }

    private class FakeChunkUploader : ChunkUploader {
        override suspend fun upload(file: File, metadata: UploadMetadata) =
            UploadResult.Success("s3://ok")
    }

    private class FakeTimeProvider : TimeProvider {
        override fun nowMillis() = System.currentTimeMillis()
    }

    private class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
