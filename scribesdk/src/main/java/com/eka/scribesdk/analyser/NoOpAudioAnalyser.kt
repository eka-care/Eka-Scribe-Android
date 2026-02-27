package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Pass-through analyser used when analysis is disabled.
 * Accepts frames but performs no processing.
 */
class NoOpAudioAnalyser : AudioAnalyser {

    override fun submitFrame(frame: AudioFrame) { /* no-op */
    }

    override val qualityFlow: Flow<AudioQuality> = emptyFlow()

    override fun release() { /* no-op */
    }
}
