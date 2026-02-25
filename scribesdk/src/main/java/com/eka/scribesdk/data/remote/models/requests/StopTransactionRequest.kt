package com.eka.scribesdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class StopTransactionRequest(
    @SerializedName("audio_files")
    val audioFiles: List<String>,
    @SerializedName("chunk_info")
    val chunkInfo: List<Map<String, ChunkData>>
)

@Keep
data class ChunkData(
    @SerializedName("st")
    val startTime: Double,
    @SerializedName("et")
    val endTime: Double
)
