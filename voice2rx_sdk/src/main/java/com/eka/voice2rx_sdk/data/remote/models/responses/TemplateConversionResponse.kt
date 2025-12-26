package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class TemplateConversionResponse(
    @SerializedName("b_id")
    var bId: String?,
    @SerializedName("message")
    var message: String?,
    @SerializedName("status")
    var status: String?,
    @SerializedName("template_id")
    var templateId: String?,
    @SerializedName("txn_id")
    var txnId: String?,
    @SerializedName("error")
    var error: TemplateConversionError? = null
)

@Keep
data class TemplateConversionError(
    @SerializedName("code")
    var code: String?,
    @SerializedName("display_message")
    var displayMessage: String?,
    @SerializedName("message")
    var message: String?
)

//{
//    "status": "failed",
//    "error": {
//    "code": "template_generation_failed",
//    "message": "not enough values to unpack (expected 4, got 3)",
//    "display_message": "An unexpected error occurred during template generation"
//},
//    "txn_id": "sc-395b0d55-27d2-4964-abf9-018feec",
//    "b_id": "7174661713699045"
//}