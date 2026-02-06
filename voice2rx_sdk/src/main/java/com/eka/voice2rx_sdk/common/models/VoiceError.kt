package com.eka.voice2rx_sdk.common.models

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeErrorDetails
import com.google.gson.annotations.SerializedName

@Keep
enum class VoiceError {
    MICROPHONE_PERMISSION_NOT_GRANTED,
    CREDENTIAL_NOT_VALID,
    UNKNOWN_ERROR,
    SUPPORTED_LANGUAGES_COUNT_EXCEEDED,
    SUPPORTED_OUTPUT_FORMATS_COUNT_EXCEEDED,
    LANGUAGE_LIST_CAN_NOT_BE_EMPTY,
    OUTPUT_FORMAT_LIST_CAN_NOT_BE_EMPTY,
    EKA_SCRIBE_INIT_ERROR,
    EKA_SCRIBE_SESSION_RECORDING_IN_PROGRESS,
    EKA_SCRIBE_SESSION_INITIALIZATION_FAILED
}

@Keep
data class EkaScribeError(
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("error")
    val errorDetails: EkaScribeErrorDetails? = null,
    @SerializedName("voice_error")
    val voiceError: VoiceError? = null,
)