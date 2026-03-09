package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class VoiceActivityData(
    @SerializedName("isSpeech")
    val isSpeech: Boolean,
    @SerializedName("amplitude")
    val amplitude: Float,
    @SerializedName("timestampMs")
    val timestampMs: Long
)
