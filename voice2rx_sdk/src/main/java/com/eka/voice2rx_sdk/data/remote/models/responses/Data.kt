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
)