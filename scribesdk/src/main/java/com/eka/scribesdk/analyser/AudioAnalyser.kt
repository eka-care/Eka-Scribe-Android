package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow

interface AudioAnalyser {
    suspend fun analyse(frame: AudioFrame): AnalysedFrame
    val qualityFlow: Flow<AudioQuality>
    fun release()
}
