package com.eka.scribesdk.encoder

import com.eka.scribesdk.recorder.AudioFrame

interface AudioEncoder {
    fun encode(frames: List<AudioFrame>, sampleRate: Int, outputPath: String): EncodedChunk
}
