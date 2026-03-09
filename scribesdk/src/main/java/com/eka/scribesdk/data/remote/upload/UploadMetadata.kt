package com.eka.scribesdk.data.remote.upload

data class UploadMetadata(
    val chunkId: String,
    val sessionId: String,
    val chunkIndex: Int,
    val fileName: String,
    val folderName: String,
    val bid: String,
    val mimeType: String = "audio/wav"
)
