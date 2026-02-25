package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame

data class AnalysedFrame(
    val frame: AudioFrame,
    val quality: AudioQuality? = null
)
