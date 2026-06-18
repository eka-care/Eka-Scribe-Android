package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class SessionData(
    val templateId: String,
    val documentId: String,
    val data: String,
)
