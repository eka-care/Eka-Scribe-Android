package com.eka.scribe_sdk.audio.analyser

import android.content.Context
import com.eka.scribe_sdk.audio.analyser.interfaces.VoiceActivityAnalyser
import com.eka.scribe_sdk.audio.models.AudioData
import com.eka.scribe_sdk.audio.models.AudioFrameSize
import com.eka.scribe_sdk.audio.models.AudioSampleRate

class AudioQualityAnalyserImpl : VoiceActivityAnalyser {
    private var mContext: Context
    private var mFrameSize: AudioFrameSize
    private var mSampleRate: AudioSampleRate

    constructor(
        context: Context,
        frameSize: AudioFrameSize = AudioFrameSize.FRAME_SIZE_512,
        sampleRate: AudioSampleRate = AudioSampleRate.SAMPLE_RATE_16K
    ) {
        this.mContext = context
        this.mFrameSize = frameSize
        this.mSampleRate = sampleRate
    }

    override fun analyseAudioData(audioData: AudioData): Result<Boolean> {
        // TODO implement
        return Result.success(true)
    }
}