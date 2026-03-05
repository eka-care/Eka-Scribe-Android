package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SessionResult(
    @SerializedName("templates")
    val templates: List<TemplateOutput>,
    @SerializedName("audioQuality")
    val audioQuality: Double?,
)
