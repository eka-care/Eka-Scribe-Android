package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UpdateTemplateResponse(
    @SerializedName("message")
    var message: String?
)