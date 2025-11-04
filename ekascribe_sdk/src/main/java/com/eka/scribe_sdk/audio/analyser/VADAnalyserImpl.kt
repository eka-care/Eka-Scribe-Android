package com.eka.scribe_sdk.audio.analyser

import com.eka.scribe_sdk.audio.analyser.interfaces.VoiceActivityAnalyser
import com.eka.scribe_sdk.audio.models.AudioData

class VADAnalyserImpl(

) : VoiceActivityAnalyser {

    override fun analyseAudioData(audioData: AudioData): Result<Boolean> {

    }
}