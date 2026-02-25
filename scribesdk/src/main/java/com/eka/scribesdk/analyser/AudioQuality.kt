package com.eka.scribesdk.analyser

data class AudioQuality(
    val snr: Float,
    val clipping: Float,
    val loudness: Float,
    val overallScore: Float
)
