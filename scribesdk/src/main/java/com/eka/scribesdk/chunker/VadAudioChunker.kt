package com.eka.scribesdk.chunker

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
    private var chunkIndex = 0
    private var chunkStartTimeMs = 0L
    private var silenceDurationMs = 0L
    private var speechDurationMs = 0L

    @Volatile
    private var latestQuality: AudioQuality? = null

    override fun feed(frame: AudioFrame): AudioChunk? {
        val vadResult = vadProvider.detect(frame.pcm)

        _activityFlow.value = VoiceActivityData(
            isSpeech = vadResult.isSpeech,
            amplitude = calculateAmplitude(frame.pcm),
            timestampMs = frame.timestampMs
        )

        if (frameAccumulator.isEmpty()) {
            chunkStartTimeMs = frame.timestampMs
        }

        frameAccumulator.add(frame)

        val frameDurationMs = (frame.pcm.size * 1000L) / frame.sampleRate

        if (vadResult.isSpeech) {
            speechDurationMs += frameDurationMs
            silenceDurationMs = 0
        } else {
            silenceDurationMs += frameDurationMs
        }

        return if (shouldChunk()) {
            createChunk(frame.timestampMs)
        } else {
            null
        }
    }

    override fun flush(): AudioChunk? {
        if (frameAccumulator.isEmpty()) return null
        val now = frameAccumulator.last().timestampMs
        return createChunk(now)
    }

    override fun setLatestQuality(quality: AudioQuality?) {
        latestQuality = quality
    }

    override fun release() {
        frameAccumulator.clear()
        latestQuality = null
        vadProvider.unload()
        logger.info(TAG, "VadAudioChunker released")
    }

    /**
     * Chunking decision logic:
     * - speechDuration > preferred AND silence > minSilence -> natural break
     * - speechDuration > desperation AND silence > despSilence -> desperation cut
     * - speechDuration >= max -> force cut
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
            quality = latestQuality
        )

        logger.debug(
            TAG,
            "Chunk #$chunkIndex created: ${chunk.durationMs}ms, ${chunk.frames.size} frames"
        )

        chunkIndex++
        frameAccumulator.clear()
        speechDurationMs = 0
        silenceDurationMs = 0

        return chunk
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
