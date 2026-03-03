package com.eka.scribesdk.analyser

data class AudioQuality(
    val stoi: Float,     // Speech Intelligibility (0.0 - 1.0)
    val pesq: Float,     // Perceptual Quality (-0.5 - 4.5)
    val siSDR: Float,    // Signal-to-Distortion Ratio (dB)
    val overallScore: Float
)
