package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.requests.AdditionalData
import com.google.gson.annotations.SerializedName

@Keep
data class Data(
    @SerializedName("output")
    val output: List<Output?>?,
    @SerializedName("additional_data")
    val additionalData: AdditionalData? = null,
    @SerializedName("audio_matrix")
    val audioQualityMetrics: AudioQuality? = null
)

@Keep
data class AudioQuality(
    @SerializedName("quality")
    val quality: Double? = null
)