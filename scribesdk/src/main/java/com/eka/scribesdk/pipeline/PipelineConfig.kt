package com.eka.scribesdk.pipeline

data class PipelineConfig(
    val frameChannelCapacity: Int = 640,
    val chunkChannelCapacity: Int = 80,
    val enableAnalyser: Boolean = true,
    val preBufferCapacity: Int = 2000
)
