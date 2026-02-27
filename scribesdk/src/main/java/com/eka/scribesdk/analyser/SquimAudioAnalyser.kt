package com.eka.scribesdk.analyser

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * SQUIM audio quality analyser that runs independently from the main pipeline.
 *
 * Frames are submitted via [submitFrame] (non-blocking, fire-and-forget).
 * Every [analysisDurationMs] (default 3 seconds) of accumulated audio,
 * ONNX inference is launched asynchronously on [scope] and results are
 * published to [qualityFlow].
 */
class SquimAudioAnalyser(
    private val modelProvider: SquimModelProvider,
    private val scope: CoroutineScope,
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
    private val lock = Any()

    override fun submitFrame(frame: AudioFrame) {
        val framesToAnalyse: List<AudioFrame>?
        synchronized(lock) {
            frameAccumulator.add(frame)
            val now = System.currentTimeMillis()
            if (now - lastAnalysisTimeMs < analysisDurationMs) {
                return
            }
            lastAnalysisTimeMs = now
            framesToAnalyse = frameAccumulator.toList()
            frameAccumulator.clear()
        }

        framesToAnalyse?.let { frames ->
            scope.launch(Dispatchers.Default) {
                runInference(frames)
            }
        }
    }

    private fun runInference(frames: List<AudioFrame>) {
        try {
            val audioData = combineFramesToFloatArray(frames)
            val quality = modelProvider.analyse(audioData)
            if (quality != null) {
                _qualityFlow.value = quality
            }
        } catch (e: Exception) {
            logger.warn(TAG, "SQUIM inference failed", e)
        }
    }

    override fun release() {
        synchronized(lock) {
            frameAccumulator.clear()
        }
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
