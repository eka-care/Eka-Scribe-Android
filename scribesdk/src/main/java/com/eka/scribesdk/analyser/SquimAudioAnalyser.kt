package com.eka.scribesdk.analyser

import android.os.Process
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

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
        private const val ANALYSIS_SAMPLE_COUNT = 16000 // Analyse only 1 second (1 chunk)
    }

    private val _qualityFlow = MutableStateFlow<AudioQuality?>(null)
    override val qualityFlow: Flow<AudioQuality> = _qualityFlow.asStateFlow().filterNotNull()

    private val frameAccumulator = mutableListOf<AudioFrame>()
    private var lastAnalysisTimeMs = 0L
    private val lock = Any()

    // Dedicated background thread with Android-level low priority via cgroup scheduling.
    // Process.THREAD_PRIORITY_BACKGROUND moves the thread to the bg cgroup,
    // which the Android scheduler actively throttles to protect UI responsiveness.
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "squim-inference").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()

    private var threadPrioritySet = false

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
            framesToAnalyse.let { frames ->
                scope.launch(inferenceDispatcher) {
                    ensureBackgroundPriority()
                    runInference(frames)
                }
            }
        }
    }

    /**
     * Set Android process-level background priority on first use.
     * This moves the thread into the bg cgroup for proper CPU throttling.
     */
    private fun ensureBackgroundPriority() {
        if (!threadPrioritySet) {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                threadPrioritySet = true
            } catch (e: Exception) {
                logger.warn(TAG, "Failed to set thread priority", e)
            }
        }
    }

    private fun runInference(frames: List<AudioFrame>) {
        try {
            // Only use the last 1 second of audio (1 inference call instead of N)
            // to minimise CPU impact while still getting quality metrics
            val audioData = combineFramesToFloatArray(frames, ANALYSIS_SAMPLE_COUNT)
            val quality = modelProvider.analyse(audioData)
            if (quality != null) {
                _qualityFlow.value = quality
            }
        } catch (e: Exception) {
            logger.warn(TAG, "SQUIM inference failed", e)
        }
    }

    override fun release() {
        try {
            synchronized(lock) {
                frameAccumulator.clear()
            }
            modelProvider.unload()
            inferenceDispatcher.close()
            logger.info(TAG, "SquimAudioAnalyser released")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to release SquimAudioAnalyser", e)
        }
    }

    /**
     * Combines frames into a normalised FloatArray, taking only the last [maxSamples]
     * to limit inference to a single 1-second chunk.
     */
    private fun combineFramesToFloatArray(
        frames: List<AudioFrame>,
        maxSamples: Int
    ): FloatArray {
        val totalSamples = frames.sumOf { it.pcm.size }
        val samplesToUse = minOf(totalSamples, maxSamples)
        val result = FloatArray(samplesToUse)

        // Take from the end (most recent audio)
        val skipSamples = totalSamples - samplesToUse
        var skipped = 0
        var offset = 0

        for (frame in frames) {
            for (sample in frame.pcm) {
                if (skipped < skipSamples) {
                    skipped++
                    continue
                }
                if (offset >= samplesToUse) break
                result[offset++] = sample.toFloat() / Short.MAX_VALUE
            }
            if (offset >= samplesToUse) break
        }

        return result
    }
}
