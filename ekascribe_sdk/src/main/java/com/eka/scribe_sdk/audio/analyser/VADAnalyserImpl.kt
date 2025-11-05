package com.eka.scribe_sdk.audio.analyser

import android.content.Context
import com.eka.scribe_sdk.audio.analyser.interfaces.VoiceActivityAnalyser
import com.eka.scribe_sdk.audio.models.AudioData
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

class VADAnalyserImpl(
    private val context: Context,
    private val vadMode: Mode = Mode.NORMAL,
    private val vadAudioSampleRate: SampleRate = SampleRate.SAMPLE_RATE_16K,
    private val vadFrameSize: FrameSize = FrameSize.FRAME_SIZE_512,
    private val defaultSilenceDurationMs: Int = 300
) : VoiceActivityAnalyser {

    val vad = Vad.builder()
        .setContext(context = context)
        .setSampleRate(sampleRate = vadAudioSampleRate)
        .setFrameSize(frameSize = vadFrameSize)
        .setMode(mode = vadMode)
        .setSilenceDurationMs(silenceDurationMs = defaultSilenceDurationMs)
        .build()

    override fun analyseAudioData(audioData: AudioData): Result<Boolean> {
        val isSpeech = vad.isSpeech(audioData = audioData.frameData.toShortArray())
        return Result.success(isSpeech)
    }
}