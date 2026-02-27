package com.eka.scribesdk.chunker

import com.eka.scribesdk.analyser.AudioQuality
import com.eka.scribesdk.api.models.VoiceActivityData
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.Flow

interface AudioChunker {
    fun feed(frame: AudioFrame): AudioChunk?
    fun flush(): AudioChunk?
    fun setLatestQuality(quality: AudioQuality?)
    val activityFlow: Flow<VoiceActivityData>
    fun release()
}
