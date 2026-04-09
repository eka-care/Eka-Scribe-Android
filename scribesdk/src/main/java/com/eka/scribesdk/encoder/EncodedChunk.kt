package com.eka.scribesdk.encoder

data class EncodedChunk(
    val filePath: String,
    val format: AudioFormat,
    val sizeBytes: Long,
    val durationMs: Long
)

enum class AudioFormat(val mimeType: String, val extension: String) {
    WAV("audio/wav", "wav"),
    MP3("audio/mpeg", "mp3"),
    MP4("audio/mp4", "mp4")
}
