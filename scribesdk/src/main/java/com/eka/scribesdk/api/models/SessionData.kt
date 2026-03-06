package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SessionData(
    @SerializedName("templateId")
    val templateId: String,
    @SerializedName("data")
    val data: String,
)
