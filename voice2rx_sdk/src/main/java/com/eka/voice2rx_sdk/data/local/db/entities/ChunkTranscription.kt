package com.eka.voice2rx_sdk.data.local.db.entities

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.eka.voice2rx_sdk.common.DatabaseConstants
import com.eka.voice2rx_sdk.common.Voice2RxUtils

/**
 * Entity for storing per-chunk transcriptions from Whisper.
 * Each transcription is linked to a session and optionally to a VoiceFile.
 */
@Keep
@Entity(
    tableName = DatabaseConstants.V2RX_CHUNK_TRANSCRIPTION_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = VToRxSession::class,
            parentColumns = ["session_id"],
            childColumns = ["foreign_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["foreign_key"]),
        Index(value = ["file_id"])
    ]
)
data class ChunkTranscription(
    @PrimaryKey
    @ColumnInfo(name = "transcription_id")
    val transcriptionId: String,

    @ColumnInfo(name = "foreign_key")
    val sessionId: String,

    @ColumnInfo(name = "file_id")
    val fileId: String? = null,  // Links to VoiceFile.fileId

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "start_time")
    val startTime: String,

    @ColumnInfo(name = "end_time")
    val endTime: String,

    @ColumnInfo(name = "language")
    val language: String = "en",

    @ColumnInfo(name = "chunk_index")
    val chunkIndex: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = Voice2RxUtils.getCurrentUTCEpochMillis()
)
