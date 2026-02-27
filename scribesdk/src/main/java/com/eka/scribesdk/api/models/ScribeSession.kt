package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class ScribeSession(
    val sessionId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,
    val chunkCount: Int,
    val uploadStage: UploadStage,
)
