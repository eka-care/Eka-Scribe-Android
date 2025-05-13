package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.local.models.Doctor
import com.eka.voice2rx_sdk.data.local.models.Patient
import com.google.gson.annotations.SerializedName

@Keep
data class AdditionalData(
    @SerializedName("doctor")
    var doctor: Doctor?,
    @SerializedName("patient")
    var patient: Patient?,
    @SerializedName("visitid")
    var visitid: String?
)