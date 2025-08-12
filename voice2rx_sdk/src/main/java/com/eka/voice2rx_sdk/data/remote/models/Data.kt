package com.eka.voice2rx_sdk.data.remote.models


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Data(
    @SerializedName("output")
    var output: Output?
)