package com.eka.scribesdk.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scribe_audio_chunk_table",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("session_id")]
)
internal data class AudioChunkEntity(
    @PrimaryKey
    @ColumnInfo(name = "chunk_id")
    val chunkId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "start_time_ms")
    val startTimeMs: Long,

    @ColumnInfo(name = "end_time_ms")
    val endTimeMs: Long,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "upload_state")
    val uploadState: String = UploadState.PENDING.name,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "quality_score")
    val qualityScore: Float? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
