package com.eka.scribesdk.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Query("SELECT * FROM scribe_session_table WHERE session_id = :sessionId")
    suspend fun getById(sessionId: String): SessionEntity?

    @Query("SELECT * FROM scribe_session_table WHERE session_id = :sessionId")
    fun observeById(sessionId: String): Flow<SessionEntity?>

    @Query("UPDATE scribe_session_table SET state = :state, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateState(sessionId: String, state: String, updatedAt: Long)

    @Query("UPDATE scribe_session_table SET chunk_count = :count, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateChunkCount(sessionId: String, count: Int, updatedAt: Long)

    @Query("DELETE FROM scribe_session_table WHERE session_id = :sessionId")
    suspend fun delete(sessionId: String)

    @Query("SELECT * FROM scribe_session_table ORDER BY created_at DESC")
    suspend fun getAll(): List<SessionEntity>

    @Query("UPDATE scribe_session_table SET upload_stage = :stage, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateUploadStage(sessionId: String, stage: String, updatedAt: Long)

    @Query("UPDATE scribe_session_table SET session_metadata = :metadata, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateSessionMetadata(sessionId: String, metadata: String, updatedAt: Long)

    @Query("SELECT * FROM scribe_session_table WHERE upload_stage = :stage ORDER BY created_at DESC")
    suspend fun getSessionsByStage(stage: String): List<SessionEntity>

    @Query("UPDATE scribe_session_table SET folder_name = :folderName, bid = :bid, updated_at = :updatedAt WHERE session_id = :sessionId")
    suspend fun updateFolderAndBid(
        sessionId: String,
        folderName: String,
        bid: String,
        updatedAt: Long
    )
}
