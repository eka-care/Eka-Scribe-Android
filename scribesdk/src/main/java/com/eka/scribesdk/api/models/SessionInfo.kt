package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SessionInfo(
    @SerializedName("sessionId")
    val sessionId: String,
    @SerializedName("state")
    val state: SessionState
)
