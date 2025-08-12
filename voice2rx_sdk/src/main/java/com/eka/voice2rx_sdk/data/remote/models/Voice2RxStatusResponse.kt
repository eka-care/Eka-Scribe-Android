package com.eka.voice2rx_sdk.data.remote.models


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStatus
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxStatusResponse(
    @SerializedName("data")
    var data: Data?,
    @SerializedName("error")
    var error: Error?,
    @SerializedName("status")
    var status: Voice2RxStatus = Voice2RxStatus.IN_PROGRESS,
)