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
    @SerializedName("signalToNoiseRatio")
    val signalToNoiseRatio: Float = 0f,
    @SerializedName("clippingDetected")
    val clippingDetected: Boolean = false,
    @SerializedName("rmsLevel")
    val rmsLevel: Float = 0f,
    @SerializedName("peakLevel")
    val peakLevel: Float = 0f,
    @SerializedName("zeroCrossingRate")
    val zeroCrossingRate: Float = 0f
)
