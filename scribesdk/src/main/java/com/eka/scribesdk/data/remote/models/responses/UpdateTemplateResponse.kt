package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
internal data class UpdateTemplateResponse(
    @SerializedName("message")
    var message: String?
)
