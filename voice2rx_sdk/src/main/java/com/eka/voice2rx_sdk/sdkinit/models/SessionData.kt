package com.eka.voice2rx_sdk.sdkinit.models

import androidx.annotation.Keep

@Keep
data class SessionData(
    val templateId: String,
    val data: String,
)
