package com.eka.voice2rx_sdk.data.local.models

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.responses.OutputType
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateId
import com.google.gson.annotations.SerializedName

@Keep
data class VoiceSessionData(
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("updated_session_id")
    val updatedSessionId: String,
    @SerializedName("created_at")
    val createdAt: Long,
    @SerializedName("full_audio_path")
    val fullAudioPath: String,
    @SerializedName("owner_id")
    val ownerId: String,
    @SerializedName("session_metadata")
    val sessionMetadata: String? = null,
    @SerializedName("status")
    val status: Voice2RxSessionStatus = Voice2RxSessionStatus.NONE,
    @SerializedName("mode")
    val mode: Voice2RxType,
    @SerializedName("outputs")
    val sessionResults: List<VoiceOutput> = emptyList(),
)

@Keep
data class VoiceOutput(
    @SerializedName("template_id")
    val templateId: TemplateId?,
    @SerializedName("type")
    val type: OutputType?,
    @SerializedName("value")
    val value: String?
)