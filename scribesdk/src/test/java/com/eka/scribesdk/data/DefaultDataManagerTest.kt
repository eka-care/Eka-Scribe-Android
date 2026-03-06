package com.eka.scribesdk.data

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.local.db.dao.AudioChunkDao
import com.eka.scribesdk.data.local.db.dao.SessionDao
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.UploadState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

internal class DefaultDataManagerTest {

    private lateinit var sessionDao: SessionDao
    private lateinit var chunkDao: AudioChunkDao
    private lateinit var timeProvider: TimeProvider
    private lateinit var logger: Logger
    private lateinit var manager: DefaultDataManager

    companion object {
        private const val SESSION_ID = "test-session"
        private const val CHUNK_ID = "test-chunk"
        private const val FIXED_TIME = 1000L
    }

    @Before
    fun setUp() {
        sessionDao = mockk(relaxed = true)
        chunkDao = mockk(relaxed = true)
        timeProvider = mockk()
        logger = mockk(relaxed = true)
        every { timeProvider.nowMillis() } returns FIXED_TIME
        manager = DefaultDataManager(sessionDao, chunkDao, timeProvider, logger)
    }

    private fun makeSession(sessionId: String = SESSION_ID) = SessionEntity(
        sessionId = sessionId,
        createdAt = FIXED_TIME,
        updatedAt = FIXED_TIME,
        state = "RECORDING"
    )

    private fun makeChunk(chunkId: String = CHUNK_ID, sessionId: String = SESSION_ID) =
        AudioChunkEntity(
            chunkId = chunkId,
            sessionId = sessionId,
            chunkIndex = 0,
            filePath = "/tmp/chunk.m4a",
            fileName = "chunk.m4a",
            startTimeMs = 0,
            endTimeMs = 10000,
            durationMs = 10000,
            createdAt = FIXED_TIME
        )

    // =====================================================================
    // SAVE
    // =====================================================================

    @Test
    fun `saveSession delegates to sessionDao insert`() = runTest {
        val session = makeSession()
        manager.saveSession(session)
        coVerify { sessionDao.insert(session) }
    }

    @Test
    fun `saveChunk delegates to chunkDao insert and updates chunk count`() = runTest {
        val chunk = makeChunk()
        coEvery { chunkDao.getChunkCount(SESSION_ID) } returns 3
        manager.saveChunk(chunk)
        coVerify { chunkDao.insert(chunk) }
        coVerify { sessionDao.updateChunkCount(SESSION_ID, 3, FIXED_TIME) }
    }

    // =====================================================================
    // GET
    // =====================================================================

    @Test
    fun `getSession delegates to sessionDao getById`() = runTest {
        val session = makeSession()
        coEvery { sessionDao.getById(SESSION_ID) } returns session
        val result = manager.getSession(SESSION_ID)
        assertEquals(session, result)
    }

    @Test
    fun `getSession returns null when not found`() = runTest {
        coEvery { sessionDao.getById(SESSION_ID) } returns null
        assertNull(manager.getSession(SESSION_ID))
    }

    @Test
    fun `getChunkCount delegates to chunkDao`() = runTest {
        coEvery { chunkDao.getChunkCount(SESSION_ID) } returns 7
        assertEquals(7, manager.getChunkCount(SESSION_ID))
    }

    @Test
    fun `getAllSessions delegates to sessionDao getAll`() = runTest {
        val sessions = listOf(makeSession("s1"), makeSession("s2"))
        coEvery { sessionDao.getAll() } returns sessions
        assertEquals(sessions, manager.getAllSessions())
    }

    @Test
    fun `getAllChunks delegates to chunkDao getBySession`() = runTest {
        val chunks = listOf(makeChunk("c1"), makeChunk("c2"))
        coEvery { chunkDao.getBySession(SESSION_ID) } returns chunks
        assertEquals(chunks, manager.getAllChunks(SESSION_ID))
    }

    // =====================================================================
    // UPDATE STATE
    // =====================================================================

    @Test
    fun `updateSessionState delegates with timestamp`() = runTest {
        manager.updateSessionState(SESSION_ID, "COMPLETED")
        coVerify { sessionDao.updateState(SESSION_ID, "COMPLETED", FIXED_TIME) }
    }

    @Test
    fun `updateChunkCount delegates with timestamp`() = runTest {
        manager.updateChunkCount(SESSION_ID, 5)
        coVerify { sessionDao.updateChunkCount(SESSION_ID, 5, FIXED_TIME) }
    }

    @Test
    fun `updateUploadStage delegates with timestamp`() = runTest {
        manager.updateUploadStage(SESSION_ID, "COMMIT")
        coVerify { sessionDao.updateUploadStage(SESSION_ID, "COMMIT", FIXED_TIME) }
    }

    @Test
    fun `updateSessionMetadata delegates with timestamp`() = runTest {
        manager.updateSessionMetadata(SESSION_ID, """{"key":"val"}""")
        coVerify { sessionDao.updateSessionMetadata(SESSION_ID, """{"key":"val"}""", FIXED_TIME) }
    }

    @Test
    fun `updateFolderAndBid delegates with timestamp`() = runTest {
        manager.updateFolderAndBid(SESSION_ID, "260302", "bid-1")
        coVerify { sessionDao.updateFolderAndBid(SESSION_ID, "260302", "bid-1", FIXED_TIME) }
    }

    // =====================================================================
    // CHUNK STATE TRANSITIONS
    // =====================================================================

    @Test
    fun `getPendingChunks delegates to chunkDao`() = runTest {
        val pending = listOf(makeChunk())
        coEvery { chunkDao.getPending(SESSION_ID) } returns pending
        assertEquals(pending, manager.getPendingChunks(SESSION_ID))
    }

    @Test
    fun `markInProgress updates state to IN_PROGRESS`() = runTest {
        manager.markInProgress(CHUNK_ID)
        coVerify { chunkDao.updateState(CHUNK_ID, UploadState.IN_PROGRESS.name) }
    }

    @Test
    fun `markUploaded updates state to SUCCESS`() = runTest {
        manager.markUploaded(CHUNK_ID)
        coVerify { chunkDao.updateState(CHUNK_ID, UploadState.SUCCESS.name) }
    }

    @Test
    fun `markFailed updates state and increments retry`() = runTest {
        manager.markFailed(CHUNK_ID)
        coVerify { chunkDao.updateStateAndIncrementRetry(CHUNK_ID, UploadState.FAILED.name) }
    }

    // =====================================================================
    // QUERY METHODS
    // =====================================================================

    @Test
    fun `getFailedChunks delegates with maxRetries`() = runTest {
        val failed = listOf(makeChunk())
        coEvery { chunkDao.getFailedChunks(SESSION_ID, 3) } returns failed
        assertEquals(failed, manager.getFailedChunks(SESSION_ID, 3))
    }

    @Test
    fun `getUploadedChunks delegates to chunkDao`() = runTest {
        val uploaded = listOf(makeChunk())
        coEvery { chunkDao.getUploadedChunks(SESSION_ID) } returns uploaded
        assertEquals(uploaded, manager.getUploadedChunks(SESSION_ID))
    }

    @Test
    fun `areAllChunksUploaded returns true when notUploadedCount is 0`() = runTest {
        coEvery { chunkDao.getNotUploadedCount(SESSION_ID) } returns 0
        assertTrue(manager.areAllChunksUploaded(SESSION_ID))
    }

    @Test
    fun `areAllChunksUploaded returns false when notUploadedCount is nonzero`() = runTest {
        coEvery { chunkDao.getNotUploadedCount(SESSION_ID) } returns 2
        assertFalse(manager.areAllChunksUploaded(SESSION_ID))
    }

    @Test
    fun `getSessionsByStage delegates to sessionDao`() = runTest {
        val sessions = listOf(makeSession())
        coEvery { sessionDao.getSessionsByStage("COMMIT") } returns sessions
        assertEquals(sessions, manager.getSessionsByStage("COMMIT"))
    }

    @Test
    fun `getRetryExhaustedChunks delegates to chunkDao`() = runTest {
        val exhausted = listOf(makeChunk())
        coEvery { chunkDao.getRetryExhaustedChunks(SESSION_ID, 3) } returns exhausted
        assertEquals(exhausted, manager.getRetryExhaustedChunks(SESSION_ID, 3))
    }

    @Test
    fun `resetRetryCount delegates to chunkDao`() = runTest {
        manager.resetRetryCount(CHUNK_ID)
        coVerify { chunkDao.resetRetryCount(CHUNK_ID) }
    }

    // =====================================================================
    // DELETE & FLOW
    // =====================================================================

    @Test
    fun `deleteSession delegates to sessionDao delete`() = runTest {
        manager.deleteSession(SESSION_ID)
        coVerify { sessionDao.delete(SESSION_ID) }
    }

    @Test
    fun `sessionFlow delegates to sessionDao observeById`() = runTest {
        val session = makeSession()
        every { sessionDao.observeById(SESSION_ID) } returns flowOf(session)
        val result = manager.sessionFlow(SESSION_ID).first()
        assertEquals(session, result)
    }
}
