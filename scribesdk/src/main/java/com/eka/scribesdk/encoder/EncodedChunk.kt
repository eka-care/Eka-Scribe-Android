package com.eka.scribesdk.encoder

data class EncodedChunk(
    val filePath: String,
    val format: AudioFormat,
    val sizeBytes: Long,
    val durationMs: Long
)

enum class AudioFormat {
    WAV,
    M4A
}
