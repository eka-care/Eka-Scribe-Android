package com.eka.voice2rx_sdk.data.local.db.entities

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.eka.voice2rx_sdk.common.DatabaseConstants
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.eka.voice2rx_sdk.data.remote.models.responses.OutputType
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateId
import com.google.gson.annotations.SerializedName

@Keep
@Entity(tableName = DatabaseConstants.V2RX_SESSION_TABLE_NAME)
data class VToRxSession(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "updated_session_id") val updatedSessionId : String,
    @ColumnInfo(name = "created_at") val createdAt : Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "full_audio_path") val fullAudioPath : String,
    @ColumnInfo(name = "owner_id") val ownerId : String,
    @ColumnInfo(name = "bid") val bid: String,
    @ColumnInfo(name = "mode") val mode : Voice2RxType,
    @ColumnInfo(name = "session_metadata") val sessionMetadata: String? = null,
    @ColumnInfo(name = "voice_transaction_state") val voiceTransactionState: VoiceTransactionState = VoiceTransactionState.STARTED,
    @ColumnInfo(name = "upload_stage") val uploadStage: VoiceTransactionStage = VoiceTransactionStage.INIT,
    @ColumnInfo(name = "status") val status: Voice2RxSessionStatus = Voice2RxSessionStatus.NONE
)

@Keep
@Entity(
    tableName = DatabaseConstants.V2RX_VOICE_FILE_TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = VToRxSession::class,
            parentColumns = ["session_id"],
            childColumns = ["foreign_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["foreign_key"]),
    ]
)
data class VoiceFile(
    @ColumnInfo(name = "foreign_key") val foreignKey: String,
    @PrimaryKey @ColumnInfo(name = "file_id") val fileId: String = "",
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "start_time") val startTime: String,
    @ColumnInfo(name = "end_time") val endTime: String,
    @ColumnInfo(name = "file_type") val fileType: VoiceFileType = VoiceFileType.CHUNK_AUDIO,
    @ColumnInfo(name = "is_uploaded") val isUploaded: Boolean = false,
)

@Keep
@Entity(
    tableName = DatabaseConstants.V2RX_VOICE_TRANSCRIPTION_OUTPUT,
    foreignKeys = [
        ForeignKey(
            entity = VToRxSession::class,
            parentColumns = ["session_id"],
            childColumns = ["foreign_key"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index(value = ["foreign_key"]),
    ]
)
data class VoiceTranscriptionOutput(
    @PrimaryKey
    @SerializedName("output_id")
    @ColumnInfo("output_id") val outputId: String,
    @SerializedName("session_id")
    @ColumnInfo(name = "foreign_key") val foreignKey: String,
    @SerializedName("name")
    @ColumnInfo(name = "name")
    val name: String?,
    @ColumnInfo(name = "template_id")
    @SerializedName("template_id")
    val templateId: TemplateId?,
    @SerializedName("type")
    @ColumnInfo(name = "type")
    val type: OutputType?,
    @SerializedName("value")
    @ColumnInfo(name = "value")
    val value: String?
)

@Keep
enum class VoiceFileType {
    CHUNK_AUDIO,
    FULL_AUDIO
}

@Keep
enum class VoiceTransactionState {
    STARTED,
    STOPPED
}

@Keep
enum class VoiceTransactionStage {
    INIT,
    STOP,
    COMMIT,
    COMPLETED,
    ERROR
}