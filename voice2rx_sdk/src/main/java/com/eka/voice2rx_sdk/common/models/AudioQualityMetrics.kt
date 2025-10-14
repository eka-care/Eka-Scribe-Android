package com.eka.voice2rx_sdk.common.models

import androidx.annotation.Keep

@Keep
data class AudioQualityMetrics(
    val rmsLevel: Float = 0f,
    val peakLevel: Float = 0f,
    val zeroCrossingRate: Float = 0f,
    val clippingDetected: Boolean = false,
    val signalToNoiseRatio: Float = 0f
)