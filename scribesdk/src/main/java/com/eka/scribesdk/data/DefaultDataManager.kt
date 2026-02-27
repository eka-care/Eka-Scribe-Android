package com.eka.scribesdk.data

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.TimeProvider
import com.eka.scribesdk.data.local.db.dao.AudioChunkDao
import com.eka.scribesdk.data.local.db.dao.SessionDao
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.UploadState
import kotlinx.coroutines.flow.Flow

internal class DefaultDataManager(
    private val sessionDao: SessionDao,
    private val chunkDao: AudioChunkDao,
    private val timeProvider: TimeProvider,
    private val logger: Logger
) : DataManager {

    companion object {
        private const val TAG = "DefaultDataManager"
    }

    override suspend fun saveSession(session: SessionEntity) {
        sessionDao.insert(session)
        logger.debug(TAG, "Session saved: ${session.sessionId}")
    }

    override suspend fun saveChunk(chunk: AudioChunkEntity) {
        chunkDao.insert(chunk)
        val count = chunkDao.getChunkCount(chunk.sessionId)
        sessionDao.updateChunkCount(chunk.sessionId, count, timeProvider.nowMillis())
        logger.debug(TAG, "Chunk saved: ${chunk.chunkId}, total=$count")
    }

    override suspend fun getSession(sessionId: String): SessionEntity? {
        return sessionDao.getById(sessionId)
    }

    override suspend fun updateSessionState(sessionId: String, state: String) {
        sessionDao.updateState(sessionId, state, timeProvider.nowMillis())
        logger.debug(TAG, "Session state updated: $sessionId -> $state")
    }

    override suspend fun updateChunkCount(sessionId: String, count: Int) {
        sessionDao.updateChunkCount(sessionId, count, timeProvider.nowMillis())
    }

    override suspend fun getPendingChunks(sessionId: String): List<AudioChunkEntity> {
        return chunkDao.getPending(sessionId)
    }

    override suspend fun markInProgress(chunkId: String) {
        chunkDao.updateState(chunkId, UploadState.IN_PROGRESS.name)
        logger.debug(TAG, "Chunk marked in-progress: $chunkId")
    }

    override suspend fun markUploaded(chunkId: String) {
        chunkDao.updateState(chunkId, UploadState.SUCCESS.name)
        logger.debug(TAG, "Chunk marked uploaded: $chunkId")
    }

    override suspend fun markFailed(chunkId: String) {
        chunkDao.updateStateAndIncrementRetry(chunkId, UploadState.FAILED.name)
        logger.debug(TAG, "Chunk marked failed: $chunkId")
    }

    override suspend fun getChunkCount(sessionId: String): Int {
        return chunkDao.getChunkCount(sessionId)
    }

    override fun sessionFlow(sessionId: String): Flow<SessionEntity?> {
        return sessionDao.observeById(sessionId)
    }

    override suspend fun deleteSession(sessionId: String) {
        sessionDao.delete(sessionId)
        logger.debug(TAG, "Session deleted: $sessionId")
    }

    override suspend fun updateUploadStage(sessionId: String, stage: String) {
        sessionDao.updateUploadStage(sessionId, stage, timeProvider.nowMillis())
        logger.debug(TAG, "Upload stage updated: $sessionId -> $stage")
    }

    override suspend fun updateSessionMetadata(sessionId: String, metadata: String) {
        sessionDao.updateSessionMetadata(sessionId, metadata, timeProvider.nowMillis())
        logger.debug(TAG, "Session metadata updated: $sessionId")
    }

    override suspend fun getFailedChunks(
        sessionId: String,
        maxRetries: Int
    ): List<AudioChunkEntity> {
        return chunkDao.getFailedChunks(sessionId, maxRetries)
    }

    override suspend fun getUploadedChunks(sessionId: String): List<AudioChunkEntity> {
        return chunkDao.getUploadedChunks(sessionId)
    }

    override suspend fun areAllChunksUploaded(sessionId: String): Boolean {
        return chunkDao.getNotUploadedCount(sessionId) == 0
    }

    override suspend fun getSessionsByStage(stage: String): List<SessionEntity> {
        return sessionDao.getSessionsByStage(stage)
    }

    override suspend fun getAllChunks(sessionId: String): List<AudioChunkEntity> {
        return chunkDao.getBySession(sessionId)
    }

    override suspend fun updateFolderAndBid(sessionId: String, folderName: String, bid: String) {
        sessionDao.updateFolderAndBid(sessionId, folderName, bid, timeProvider.nowMillis())
        logger.debug(TAG, "Folder/bid updated: $sessionId -> $folderName, $bid")
    }

    override suspend fun getAllSessions(): List<SessionEntity> {
        return sessionDao.getAll()
    }
}
