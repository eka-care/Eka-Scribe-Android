package com.eka.voice2rx_sdk.common.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class VoiceActivityData(
    @SerializedName("isSpeech")
    val isSpeech: Boolean = false,
    @SerializedName("amplitude")
    val amplitude: Float = 0f,
    @SerializedName("timeStamp")
    val timeStamp: Long = 0L,
)
