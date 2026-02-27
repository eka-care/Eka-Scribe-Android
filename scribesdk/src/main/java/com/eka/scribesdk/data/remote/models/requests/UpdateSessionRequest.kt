package com.eka.scribesdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

internal class UpdateSessionRequest : ArrayList<UpdateSessionRequest.UpdateSessionRequestItem>() {
    @Keep
    data class UpdateSessionRequestItem(
        @SerializedName("data")
        var data: String,
        @SerializedName("template-id")
        var templateId: String
    )
}
