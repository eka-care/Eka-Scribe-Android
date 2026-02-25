package com.eka.scribesdk.analyser

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class SquimAudioAnalyser(
    private val modelProvider: SquimModelProvider,
    private val analysisDurationMs: Long = 3000L,
    private val logger: Logger
) : AudioAnalyser {

    companion object {
        private const val TAG = "SquimAudioAnalyser"
    }

    private val _qualityFlow = MutableStateFlow<AudioQuality?>(null)
    override val qualityFlow: Flow<AudioQuality> = _qualityFlow.asStateFlow().filterNotNull()

    private val frameAccumulator = mutableListOf<AudioFrame>()
    private var lastAnalysisTimeMs = 0L

    override suspend fun analyse(frame: AudioFrame): AnalysedFrame {
        frameAccumulator.add(frame)

        val now = System.currentTimeMillis()
        if (now - lastAnalysisTimeMs < analysisDurationMs) {
            return AnalysedFrame(frame = frame, quality = null)
        }

        lastAnalysisTimeMs = now

        val audioData = combineFramesToFloatArray(frameAccumulator)
        frameAccumulator.clear()

        val quality = modelProvider.analyse(audioData)
        if (quality != null) {
            _qualityFlow.value = quality
        }

        return AnalysedFrame(frame = frame, quality = quality)
    }

    override fun release() {
        frameAccumulator.clear()
        modelProvider.unload()
        logger.info(TAG, "SquimAudioAnalyser released")
    }

    private fun combineFramesToFloatArray(frames: List<AudioFrame>): FloatArray {
        val totalSamples = frames.sumOf { it.pcm.size }
        val result = FloatArray(totalSamples)
        var offset = 0
        for (frame in frames) {
            for (sample in frame.pcm) {
                result[offset++] = sample.toFloat() / Short.MAX_VALUE
            }
        }
        return result
    }
}
