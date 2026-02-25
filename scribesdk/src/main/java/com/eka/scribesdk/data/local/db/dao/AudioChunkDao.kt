package com.eka.scribesdk.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity

@Dao
interface AudioChunkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: AudioChunkEntity)

    @Query("SELECT * FROM scribe_audio_chunk_table WHERE session_id = :sessionId ORDER BY chunk_index ASC")
    suspend fun getBySession(sessionId: String): List<AudioChunkEntity>

    @Query("SELECT * FROM scribe_audio_chunk_table WHERE session_id = :sessionId AND upload_state = 'PENDING' ORDER BY chunk_index ASC")
    suspend fun getPending(sessionId: String): List<AudioChunkEntity>

    @Query("SELECT * FROM scribe_audio_chunk_table WHERE chunk_id = :chunkId")
    suspend fun getByChunkId(chunkId: String): AudioChunkEntity?

    @Query("UPDATE scribe_audio_chunk_table SET upload_state = :state WHERE chunk_id = :chunkId")
    suspend fun updateState(chunkId: String, state: String)

    @Query("UPDATE scribe_audio_chunk_table SET upload_state = :state, retry_count = retry_count + 1 WHERE chunk_id = :chunkId")
    suspend fun updateStateAndIncrementRetry(chunkId: String, state: String)

    @Query("DELETE FROM scribe_audio_chunk_table WHERE session_id = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT COUNT(*) FROM scribe_audio_chunk_table WHERE session_id = :sessionId")
    suspend fun getChunkCount(sessionId: String): Int

    @Query("SELECT * FROM scribe_audio_chunk_table WHERE session_id = :sessionId AND upload_state = 'FAILED' AND retry_count < :maxRetries ORDER BY chunk_index ASC")
    suspend fun getFailedChunks(sessionId: String, maxRetries: Int): List<AudioChunkEntity>

    @Query("SELECT * FROM scribe_audio_chunk_table WHERE session_id = :sessionId AND upload_state = 'SUCCESS' ORDER BY chunk_index ASC")
    suspend fun getUploadedChunks(sessionId: String): List<AudioChunkEntity>

    @Query("SELECT COUNT(*) FROM scribe_audio_chunk_table WHERE session_id = :sessionId AND upload_state != 'SUCCESS'")
    suspend fun getNotUploadedCount(sessionId: String): Int
}
