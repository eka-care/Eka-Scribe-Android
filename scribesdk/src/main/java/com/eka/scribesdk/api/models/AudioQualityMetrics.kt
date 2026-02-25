package com.eka.scribesdk.api.models

data class AudioQualityMetrics(
    val snr: Float,
    val clipping: Float,
    val loudness: Float,
    val overallScore: Float
)
