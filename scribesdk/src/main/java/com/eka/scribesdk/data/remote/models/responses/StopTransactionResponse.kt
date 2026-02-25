package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class StopTransactionResponse(
    @SerializedName("message")
    val message: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("error")
    val error: String?
)
