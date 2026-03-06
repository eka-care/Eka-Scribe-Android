package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class AudioQualityMetrics(
    @SerializedName("stoi")
    val stoi: Float,     // Speech Intelligibility (0.0 - 1.0)
    @SerializedName("pesq")
    val pesq: Float,     // Perceptual Quality (-0.5 - 4.5)
    @SerializedName("siSDR")
    val siSDR: Float,    // Signal-to-Distortion Ratio (dB)
    @SerializedName("overallScore")
    val overallScore: Float
)
