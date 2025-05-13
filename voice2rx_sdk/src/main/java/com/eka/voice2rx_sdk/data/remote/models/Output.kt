package com.eka.voice2rx_sdk.data.remote.models


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Output(
    @SerializedName("fhir")
    var fhir: String?
)