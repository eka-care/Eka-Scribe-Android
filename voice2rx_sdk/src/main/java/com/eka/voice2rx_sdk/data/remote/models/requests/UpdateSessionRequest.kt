package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

class UpdateSessionRequest : ArrayList<UpdateSessionRequest.UpdateSessionRequestItem>() {
    @Keep
    data class UpdateSessionRequestItem(
        @SerializedName("data")
        var data: String,
        @SerializedName("template-id")
        var templateId: String
    )
}