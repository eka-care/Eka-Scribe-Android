package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class OutputFormatTemplate(
    @SerializedName("template_id")
    var templateId: String?
)