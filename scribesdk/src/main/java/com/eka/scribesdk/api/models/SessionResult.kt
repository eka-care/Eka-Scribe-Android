package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class SessionResult(
    val templates: List<TemplateOutput>,
    val audioQuality: Double?,
)
