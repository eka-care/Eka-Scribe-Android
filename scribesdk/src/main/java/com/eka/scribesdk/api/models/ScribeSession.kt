package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ScribeSession(
    @SerializedName("sessionId")
    val sessionId: String,
    @SerializedName("createdAt")
    val createdAt: Long,
    @SerializedName("updatedAt")
    val updatedAt: Long,
    @SerializedName("state")
    val state: String,
    @SerializedName("chunkCount")
    val chunkCount: Int,
    @SerializedName("uploadStage")
    val uploadStage: UploadStage,
)
