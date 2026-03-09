package com.eka.scribesdk.chunker

interface VadProvider {
    fun load()
    fun detect(pcm: ShortArray): VadResult
    fun unload()
}

data class VadResult(
    val isSpeech: Boolean,
    val confidence: Float
)
