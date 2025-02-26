package com.eka.voice2rx_sdk.data.local.db.entities

import androidx.annotation.Keep
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.eka.voice2rx_sdk.common.DatabaseConstants
import com.eka.voice2rx_sdk.data.local.models.Voice2RxSessionStatus
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType

@Keep
@Entity(tableName = DatabaseConstants.V2RX_SESSION_TABLE_NAME)
data class VToRxSession(
    @PrimaryKey(autoGenerate = true) val id : Int = 0,
    @ColumnInfo(name = "session_id") val sessionId : String,
    @ColumnInfo(name = "updated_session_id") val updatedSessionId : String,
    @ColumnInfo(name = "file_paths") val filePaths : List<String>,
    @ColumnInfo(name = "created_at") val createdAt : Long,
    @ColumnInfo(name = "full_audio_path") val fullAudioPath : String,
    @ColumnInfo(name = "owner_id") val ownerId : String,
    @ColumnInfo(name = "caller_id") val callerId : String,
    @ColumnInfo(name = "patient_id") val patientId : String,
    @ColumnInfo(name = "mode") val mode : Voice2RxType,
    @ColumnInfo(name = "structured_rx") val structuredRx : String?,
    @ColumnInfo(name = "transcript") val transcript : String?,
    @ColumnInfo(name = "is_processed") val isProcessed : Boolean,
    @ColumnInfo(name = "status") val status : Voice2RxSessionStatus = Voice2RxSessionStatus.DRAFT,
)
