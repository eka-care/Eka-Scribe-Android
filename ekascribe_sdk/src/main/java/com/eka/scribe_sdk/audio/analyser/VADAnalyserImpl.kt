package com.eka.scribe_sdk.audio.analyser

import android.content.Context
import com.eka.scribe_sdk.audio.analyser.interfaces.VoiceActivityAnalyser
import com.eka.scribe_sdk.audio.models.AudioData
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

class VADAnalyserImpl : VoiceActivityAnalyser {
    private var vad: VadSilero? = null
    private var mContext: Context
    private var mVadMode: Mode
    private var mVadFrameSize: FrameSize
    private var mVadSampleRate: SampleRate
    private var mSilenceDurationInMs: Int

    constructor(
        context: Context,
        vadMode: Mode = Mode.NORMAL,
        vadAudioSampleRate: SampleRate = SampleRate.SAMPLE_RATE_16K,
        vadFrameSize: FrameSize = FrameSize.FRAME_SIZE_512,
        defaultSilenceDurationMs: Int = 300
    ) {
        this.mContext = context
        this.mVadMode = vadMode
        this.mVadFrameSize = vadFrameSize
        this.mVadSampleRate = vadAudioSampleRate
        this.mSilenceDurationInMs = defaultSilenceDurationMs
    }

    override fun analyseAudioData(audioData: AudioData): Result<Boolean> {
        val isSpeech = getInstance().isSpeech(audioData = audioData.frameData.toShortArray())
        return Result.success(isSpeech)
    }

    private fun getInstance(): VadSilero {
        if (vad == null) {
            vad = Vad.builder()
                .setContext(context = mContext)
                .setSampleRate(sampleRate = mVadSampleRate)
                .setFrameSize(frameSize = mVadFrameSize)
                .setMode(mode = mVadMode)
                .setSilenceDurationMs(silenceDurationMs = mSilenceDurationInMs)
                .build()
            return vad!!
        } else {
            return vad!!
        }
    }
}