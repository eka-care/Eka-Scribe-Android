package com.eka.scribesdk.pipeline

data class PipelineConfig(
    val frameChannelCapacity: Int = 64,
    val chunkChannelCapacity: Int = 8,
    val enableAnalyser: Boolean = true,
    val preBufferCapacity: Int = 200
)
