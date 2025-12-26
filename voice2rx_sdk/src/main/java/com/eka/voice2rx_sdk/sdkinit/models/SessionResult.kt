package com.eka.voice2rx_sdk.sdkinit.models

import androidx.annotation.Keep

@Keep
data class SessionResult(
    val templates: List<TemplateOutput>,
    val audioQuality: Double?,
)
