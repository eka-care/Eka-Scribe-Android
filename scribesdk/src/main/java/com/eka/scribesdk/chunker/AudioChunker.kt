package com.eka.scribesdk.chunker

import com.eka.scribesdk.analyser.AnalysedFrame
import com.eka.scribesdk.api.models.VoiceActivityData
import kotlinx.coroutines.flow.Flow

interface AudioChunker {
    fun feed(frame: AnalysedFrame): AudioChunk?
    fun flush(): AudioChunk?
    val activityFlow: Flow<VoiceActivityData>
    fun release()
}
