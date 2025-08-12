package com.eka.voice2rx_sdk.data.remote.models


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Error(
    @SerializedName("code")
    var code: String?,
    @SerializedName("message")
    var message: String?
)