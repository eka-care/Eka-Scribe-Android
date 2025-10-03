package com.eka.voice2rx_sdk.common.models

import androidx.annotation.Keep

@Keep
data class VoiceActivityData(
    val isSpeech: Boolean = false,
    val amplitude: Float = 0f,
    val timeStamp: Long = 0L,
)
