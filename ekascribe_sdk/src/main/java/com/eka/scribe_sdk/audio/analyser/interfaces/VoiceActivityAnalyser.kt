package com.eka.scribe_sdk.audio.analyser.interfaces

import com.eka.scribe_sdk.audio.models.AudioData

interface VoiceActivityAnalyser {
    fun analyseAudioData(audioData: AudioData): Result<Boolean>
}