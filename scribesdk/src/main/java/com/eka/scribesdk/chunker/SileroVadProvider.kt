package com.eka.scribesdk.chunker

import android.content.Context
import com.eka.scribesdk.common.logging.Logger
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

class SileroVadProvider(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
    private val logger: Logger
) : VadProvider {

    companion object {
        private const val TAG = "SileroVadProvider"
    }

    private var vad: VadSilero? = null

    override fun load() {
        try {
            val sileroSampleRate = when (sampleRate) {
                8000 -> SampleRate.SAMPLE_RATE_8K
                16000 -> SampleRate.SAMPLE_RATE_16K
                else -> SampleRate.SAMPLE_RATE_16K
            }

            val sileroFrameSize = when (frameSize) {
                256 -> FrameSize.FRAME_SIZE_256
                512 -> FrameSize.FRAME_SIZE_512
                768 -> FrameSize.FRAME_SIZE_768
                1024 -> FrameSize.FRAME_SIZE_1024
                1536 -> FrameSize.FRAME_SIZE_1536
                else -> FrameSize.FRAME_SIZE_512
            }

            vad = Vad.builder()
                .setContext(context)
                .setSampleRate(sileroSampleRate)
                .setFrameSize(sileroFrameSize)
                .setMode(Mode.NORMAL)
                .setSilenceDurationMs(300)
                .setSpeechDurationMs(50)
                .build()

            logger.info(TAG, "Silero VAD loaded: ${sampleRate}Hz, frame=$frameSize")
        } catch (e: Exception) {
            logger.error(TAG, "Failed to load Silero VAD", e)
            throw e
        }
    }

    override fun detect(pcm: ShortArray): VadResult {
        val vadInstance = vad ?: return VadResult(isSpeech = false, confidence = 0f)
        val isSpeech = vadInstance.isSpeech(pcm)
        return VadResult(isSpeech = isSpeech, confidence = if (isSpeech) 1f else 0f)
    }

    override fun unload() {
        vad?.close()
        vad = null
        logger.info(TAG, "Silero VAD unloaded")
    }
}
