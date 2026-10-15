package com.eka.scribesdk.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eka.scribesdk.api.models.ScribeSession
import com.eka.scribesdk.api.models.UploadStage

@Entity(tableName = "scribe_session_table")
internal data class SessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "state")
    val state: String,

    @ColumnInfo(name = "chunk_count")
    val chunkCount: Int = 0,

    @ColumnInfo(name = "mode")
    val mode: String? = null,

    @ColumnInfo(name = "owner_id")
    val ownerId: String? = null,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null,

    @ColumnInfo(name = "upload_stage")
    val uploadStage: String = TransactionStage.INIT.name,

    @ColumnInfo(name = "session_metadata")
    val sessionMetadata: String? = null,

    @ColumnInfo(name = "folder_name")
    val folderName: String? = null,

    @ColumnInfo(name = "bid")
    val bid: String? = null,

    /** Backend flow this session was created on: null = legacy transaction APIs, see [ApiFlow]. */
    @ColumnInfo(name = "api_flow")
    val apiFlow: String? = null
)

/** Values for [SessionEntity.apiFlow]. Legacy (transaction v2 + S3) sessions keep null. */
internal object ApiFlow {
    const val SESSIONS_V1 = "sessions_v1"

    /** Value to store for a session created now. */
    fun forNewSession(useSessionsApi: Boolean): String? = if (useSessionsApi) SESSIONS_V1 else null
}

internal fun SessionEntity.toScribeSession(): ScribeSession {
    val stage = try {
        UploadStage.valueOf(uploadStage)
    } catch (e: Exception) {
        UploadStage.INIT
    }
    return ScribeSession(
        sessionId = sessionId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        state = state,
        chunkCount = chunkCount,
        uploadStage = stage,
    )
}
