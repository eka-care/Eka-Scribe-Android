package com.eka.scribesdk.data

import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

interface DataManager {
    suspend fun saveSession(session: SessionEntity)
    suspend fun saveChunk(chunk: AudioChunkEntity)
    suspend fun getSession(sessionId: String): SessionEntity?
    suspend fun updateSessionState(sessionId: String, state: String)
    suspend fun updateChunkCount(sessionId: String, count: Int)
    suspend fun getPendingChunks(sessionId: String): List<AudioChunkEntity>
    suspend fun markInProgress(chunkId: String)
    suspend fun markUploaded(chunkId: String)
    suspend fun markFailed(chunkId: String)
    suspend fun getChunkCount(sessionId: String): Int
    fun sessionFlow(sessionId: String): Flow<SessionEntity?>
    suspend fun deleteSession(sessionId: String)
    suspend fun updateUploadStage(sessionId: String, stage: String)
    suspend fun updateSessionMetadata(sessionId: String, metadata: String)
    suspend fun getFailedChunks(sessionId: String, maxRetries: Int): List<AudioChunkEntity>
    suspend fun getUploadedChunks(sessionId: String): List<AudioChunkEntity>
    suspend fun areAllChunksUploaded(sessionId: String): Boolean
    suspend fun getSessionsByStage(stage: String): List<SessionEntity>
    suspend fun getAllChunks(sessionId: String): List<AudioChunkEntity>
    suspend fun updateFolderAndBid(sessionId: String, folderName: String, bid: String)
}
