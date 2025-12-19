package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UpdateUserConfigResponse(
    @SerializedName("message")
    var message: String?,
    @SerializedName("result")
    var result: Result?
) {
    @Keep
    data class Result(
        @SerializedName("action")
        var action: String?,
        @SerializedName("b_id")
        var bId: String?,
        @SerializedName("user_uuid")
        var userUuid: String?
    )
}