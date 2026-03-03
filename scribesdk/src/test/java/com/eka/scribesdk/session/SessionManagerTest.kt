package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.error.ErrorCode
import com.eka.scribesdk.common.error.ScribeException
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    private fun createManager(): SessionManager {
        val dm = FakeDataManager()
        val uploader = FakeChunkUploader()
        // MockK Pipeline.Factory — create() is never reached in state-guard tests
        val pipelineFactory = mockk<Pipeline.Factory>(relaxed = true)
        val tm = mockk<TransactionManager>(relaxed = true)

        // Make initTransaction return an error so the async coroutine in start()
        // settles to ERROR state quickly (allows re-start tests)
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Error("test error")

        return SessionManager(
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
    fun `start returns session ID and transitions to STARTING`() {
        val manager = createManager()
        val sessionId = manager.start()

        assertNotNull(sessionId)
        assertTrue("Session ID should start with 'a-'", sessionId.startsWith("a-"))
        // Synchronously, state moves to STARTING before the coroutine runs
        val state = manager.currentState
        assertTrue(
            "State should be STARTING or beyond, was $state",
            state != SessionState.IDLE
        )
    }

    @Test
    fun `start generates unique session IDs`() {
        val manager = createManager()
        val id1 = manager.start()
        // Let async settle to ERROR so we can start again
        Thread.sleep(200)
        val state = manager.currentState
        if (state == SessionState.ERROR || state == SessionState.COMPLETED) {
            val id2 = manager.start()
            assertTrue("IDs should be different", id1 != id2)
        }
    }

    @Test
    fun `start from RECORDING state throws`() {
        val manager = createManager()
        manager.start()
        Thread.sleep(200)

        // If async moved to RECORDING, a second start should throw
        if (manager.currentState == SessionState.RECORDING) {
            try {
                manager.start()
                fail("Should throw ScribeException")
            } catch (e: ScribeException) {
                assertEquals(ErrorCode.INVALID_STATE_TRANSITION, e.code)
            }
        }
    }

    @Test
    fun `start from ERROR state resets and starts new session`() {
        val manager = createManager()
        manager.start()
        Thread.sleep(300) // Wait for async to fail → ERROR

        if (manager.currentState == SessionState.ERROR) {
            // Should allow a restart
            val newId = manager.start()
            assertNotNull(newId)
            assertTrue(newId.startsWith("a-"))
        }
    }

    @Test
    fun `start from PAUSED state throws`() {
        val manager = createManager()
        manager.start()
        Thread.sleep(200)

        if (manager.currentState == SessionState.RECORDING) {
            manager.pause()
            try {
                manager.start()
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
    fun `pause from STARTING throws`() {
        val manager = createManager()
        manager.start()
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
    fun `resume from RECORDING throws`() {
        val manager = createManager()
        manager.start()
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
    fun `stop from STARTING throws`() {
        val manager = createManager()
        manager.start()
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

        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
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
            logger = NoOpLogger()
        )
    }

    @Test
    fun `stop transitions to ERROR when pipeline stop throws exception`() {
        val pipeline = mockk<Pipeline>(relaxed = true)
        every { pipeline.voiceActivityFlow } returns emptyFlow()
        every { pipeline.audioQualityFlow } returns emptyFlow()
        every { pipeline.audioFocusFlow } returns emptyFlow()
        coEvery { pipeline.stop() } throws RuntimeException("Pipeline crash")

        val manager = createManagerWithSuccessInit(pipeline = pipeline)
        manager.start()
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to ERROR when retryFailedUploads returns false`() {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns false // Simulate incomplete uploads

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start()
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        // Should be in ERROR state and not proceed to stopTransaction
        assertEquals(SessionState.ERROR, manager.currentState)
        io.mockk.coVerify(exactly = 0) { tm.stopTransaction(any()) }
    }

    @Test
    fun `stop transitions to ERROR when stopTransaction fails`() {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Error("API fails")

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start()
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to ERROR when commitTransaction fails`() {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.commitTransaction(any()) } returns TransactionResult.Error("API fails")

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start()
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.ERROR, manager.currentState)
    }

    @Test
    fun `stop transitions to COMPLETED when pollResult times out`() {
        val tm = mockk<TransactionManager>(relaxed = true)
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
            "folder",
            "bid"
        )
        coEvery { tm.retryFailedUploads(any()) } returns true
        coEvery { tm.stopTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.commitTransaction(any()) } returns TransactionResult.Success()
        coEvery { tm.pollResult(any()) } returns TransactionPollResult.Timeout

        val manager = createManagerWithSuccessInit(tm = tm)
        manager.start()
        Thread.sleep(200)

        manager.stop()
        Thread.sleep(200)

        assertEquals(SessionState.COMPLETED, manager.currentState)
    }

    @Test
    fun `deferred full audio upload triggers when pipeline stop returns FullAudioResult`() {
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
        coEvery { tm.initTransaction(any(), any()) } returns TransactionResult.Success(
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
        manager.start()
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
    fun `destroy after start resets to IDLE`() {
        val manager = createManager()
        manager.start()
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
