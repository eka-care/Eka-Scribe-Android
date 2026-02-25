package com.eka.scribesdk.pipeline

data class PipelineHealth(
    val frameQueueUtilization: Float,
    val chunkQueueUtilization: Float,
    val memoryUsageMb: Long,
    val isDegraded: Boolean
)
