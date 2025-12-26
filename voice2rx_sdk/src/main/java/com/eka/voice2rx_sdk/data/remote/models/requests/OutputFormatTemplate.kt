package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class OutputFormatTemplate(
    @SerializedName("template_id")
    var templateId: String?,
    @SerializedName("template_type")
    var type: String = "custom",
    @SerializedName("template_name")
    var name: String?
)