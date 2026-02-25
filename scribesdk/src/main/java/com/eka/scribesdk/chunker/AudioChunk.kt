package com.eka.scribesdk.chunker

import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.recorder.AudioFrame

data class AudioChunk(
    val chunkId: String,
    val sessionId: String,
    val index: Int,
    val frames: List<AudioFrame>,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val quality: AudioQuality? = null,
    val durationMs: Long = endTimeMs - startTimeMs
)
