package com.eka.scribesdk.chunker

import com.eka.scribesdk.analyser.AnalysedFrame
import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.common.util.IdGenerator
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class VadAudioChunker(
    private val vadProvider: VadProvider,
    private val config: ChunkConfig,
    private val sessionId: String,
    private val logger: Logger
) : AudioChunker {

    companion object {
        private const val TAG = "VadAudioChunker"
    }

    private val _activityFlow = MutableStateFlow<VoiceActivityData?>(null)
    override val activityFlow: Flow<VoiceActivityData> = _activityFlow.asStateFlow().filterNotNull()

    private val frameAccumulator = mutableListOf<AudioFrame>()
    private val qualityAccumulator = mutableListOf<AudioQuality>()
    private var chunkIndex = 0
    private var chunkStartTimeMs = 0L
    private var silenceDurationMs = 0L
    private var speechDurationMs = 0L

    override fun feed(frame: AnalysedFrame): AudioChunk? {
        val vadResult = vadProvider.detect(frame.frame.pcm)

        _activityFlow.value = VoiceActivityData(
            isSpeech = vadResult.isSpeech,
            amplitude = calculateAmplitude(frame.frame.pcm),
            timestampMs = frame.frame.timestampMs
        )

        if (frameAccumulator.isEmpty()) {
            chunkStartTimeMs = frame.frame.timestampMs
        }

        frameAccumulator.add(frame.frame)
        frame.quality?.let { qualityAccumulator.add(it) }

        val frameDurationMs = (frame.frame.pcm.size * 1000L) / frame.frame.sampleRate

        if (vadResult.isSpeech) {
            speechDurationMs += frameDurationMs
            silenceDurationMs = 0
        } else {
            silenceDurationMs += frameDurationMs
        }

        return if (shouldChunk()) {
            createChunk(frame.frame.timestampMs)
        } else {
            null
        }
    }

    override fun flush(): AudioChunk? {
        if (frameAccumulator.isEmpty()) return null
        val now = frameAccumulator.last().timestampMs
        return createChunk(now)
    }

    override fun release() {
        frameAccumulator.clear()
        qualityAccumulator.clear()
        vadProvider.unload()
        logger.info(TAG, "VadAudioChunker released")
    }

    /**
     * Chunking decision logic from LLD:
     * - speechDuration > preferred AND silence > minSilence → natural break
     * - speechDuration > desperation AND silence > despSilence → desperation cut
     * - speechDuration >= max → force cut
     */
    private fun shouldChunk(): Boolean {
        if (speechDurationMs > config.preferredDurationMs && silenceDurationMs > config.minSilenceToChunkMs) {
            return true
        }
        if (speechDurationMs > config.desperationDurationMs && silenceDurationMs > config.despSilenceToChunkMs) {
            return true
        }
        if (speechDurationMs >= config.maxDurationMs) {
            return true
        }
        return false
    }

    private fun createChunk(endTimeMs: Long): AudioChunk {
        val chunk = AudioChunk(
            chunkId = IdGenerator.chunkId(sessionId, chunkIndex),
            sessionId = sessionId,
            index = chunkIndex,
            frames = frameAccumulator.toList(),
            startTimeMs = chunkStartTimeMs,
            endTimeMs = endTimeMs,
            quality = averageQuality()
        )

        logger.debug(
            TAG,
            "Chunk #$chunkIndex created: ${chunk.durationMs}ms, ${chunk.frames.size} frames"
        )

        chunkIndex++
        frameAccumulator.clear()
        qualityAccumulator.clear()
        speechDurationMs = 0
        silenceDurationMs = 0

        return chunk
    }

    private fun averageQuality(): AudioQuality? {
        if (qualityAccumulator.isEmpty()) return null
        return AudioQuality(
            snr = qualityAccumulator.map { it.snr }.average().toFloat(),
            clipping = qualityAccumulator.map { it.clipping }.average().toFloat(),
            loudness = qualityAccumulator.map { it.loudness }.average().toFloat(),
            overallScore = qualityAccumulator.map { it.overallScore }.average().toFloat()
        )
    }

    private fun calculateAmplitude(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return 0f
        var sum = 0L
        for (sample in pcm) {
            sum += sample * sample
        }
        return kotlin.math.sqrt(sum.toDouble() / pcm.size).toFloat()
    }
}
