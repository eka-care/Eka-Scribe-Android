package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow

interface AudioAnalyser {
    fun submitFrame(frame: AudioFrame)
    val qualityFlow: Flow<AudioQuality>
    fun release()
}
