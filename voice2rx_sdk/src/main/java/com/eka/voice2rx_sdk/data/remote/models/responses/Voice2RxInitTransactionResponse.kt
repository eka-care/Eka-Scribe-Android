package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxInitTransactionResponse(
    @SerializedName("b_id")
    var bId: String?,
    @SerializedName("message")
    var message: String?,
    @SerializedName("status")
    var status: String?,
    @SerializedName("txn_id")
    var txnId: String?,
    @SerializedName("error")
    var error: EkaScribeErrorDetails?
)

@Keep
data class EkaScribeErrorDetails(
    @SerializedName("code")
    var code: String?,
    @SerializedName("display_message")
    var displayMessage: String?,
    @SerializedName("message")
    var message: String?
)