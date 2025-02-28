package com.eka.voice2rx_sdk.common.models

import androidx.annotation.Keep

@Keep
enum class VoiceError {
    MICROPHONE_PERMISSION_NOT_GRANTED,
    CREDENTIAL_NOT_VALID,
    UNKNOWN_ERROR,
}