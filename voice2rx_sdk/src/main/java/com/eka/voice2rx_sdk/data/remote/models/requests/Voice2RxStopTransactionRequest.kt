package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxStopTransactionRequest(
    @SerializedName("audio_files")
    var audioFiles: List<String?>?,
    @SerializedName("chunk_info")
    val chunksInfo: List<Map<String, ChunkData>>
)

@Keep
data class ChunkData(
    @SerializedName("st")
    val startTime: Double,

    @SerializedName("et")
    val endTime: Double
)