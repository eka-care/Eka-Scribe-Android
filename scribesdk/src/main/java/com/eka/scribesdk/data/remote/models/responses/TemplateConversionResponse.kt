package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
internal data class TemplateConversionResponse(
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
internal data class TemplateConversionError(
    @SerializedName("code")
    var code: String?,
    @SerializedName("display_message")
    var displayMessage: String?,
    @SerializedName("message")
    var message: String?
)
