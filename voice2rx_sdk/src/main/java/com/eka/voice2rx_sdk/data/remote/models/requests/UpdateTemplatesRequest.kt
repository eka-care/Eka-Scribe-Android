package com.eka.voice2rx_sdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UpdateTemplatesRequest(
    @SerializedName("data")
    var data: Data?,
    @SerializedName("request_type")
    var requestType: String = "user"
) {
    @Keep
    data class Data(
        @SerializedName("my_templates")
        var myTemplates: List<String>
    )
}