package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pass-through analyser used when analysis is disabled or
 * during degraded mode. Wraps the frame without running any model.
 */
class NoOpAudioAnalyser : AudioAnalyser {

    override suspend fun analyse(frame: AudioFrame): AnalysedFrame {
        return AnalysedFrame(frame = frame, quality = null)
    }

    override val qualityFlow: Flow<AudioQuality> = emptyFlow()

    override fun release() { /* no-op */
    }
}
