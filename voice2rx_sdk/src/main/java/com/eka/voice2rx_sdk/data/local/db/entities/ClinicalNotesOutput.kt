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
 * Entity for storing generated clinical notes from LLM.
 * Each clinical note is linked to a session.
 */
@Keep
@Entity(
    tableName = DatabaseConstants.V2RX_CLINICAL_NOTES_TABLE,
    foreignKeys = [
        ForeignKey(
            entity = VToRxSession::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["session_id"])
    ]
)
data class ClinicalNotesOutput(
    @PrimaryKey
    @ColumnInfo(name = "note_id")
    val noteId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "markdown_content")
    val markdownContent: String,

    @ColumnInfo(name = "generated_at")
    val generatedAt: Long = Voice2RxUtils.getCurrentUTCEpochMillis(),

    @ColumnInfo(name = "model_version")
    val modelVersion: String = "gemma3-1b-it",

    @ColumnInfo(name = "input_transcript")
    val inputTranscript: String = "",

    @ColumnInfo(name = "generation_status")
    val generationStatus: String = ClinicalNotesStatus.COMPLETED.name
)

enum class ClinicalNotesStatus {
    PENDING,
    GENERATING,
    COMPLETED,
    FAILED
}
