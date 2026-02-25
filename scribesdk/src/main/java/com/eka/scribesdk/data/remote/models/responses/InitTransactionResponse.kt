package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class InitTransactionResponse(
    @SerializedName("b_id")
    val bId: String?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("txn_id")
    val txnId: String?,
    @SerializedName("error")
    val error: ErrorDetails?
)

@Keep
data class ErrorDetails(
    @SerializedName("code")
    val code: String?,
    @SerializedName("display_message")
    val displayMessage: String?,
    @SerializedName("message")
    val message: String?
)
