package com.eka.scribesdk.pipeline

import com.eka.scribesdk.pipeline.stage.PreBuffer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Observes pipeline health: queue depths, memory pressure.
 * Used by [DegradationPolicy] to decide whether to skip optional stages.
 */
class PipelineMonitor(
    private val preBuffer: PreBuffer,
    private val frameChannelCapacity: Int,
    private val chunkChannelCapacity: Int
) {
    private val _healthFlow = MutableStateFlow(
        PipelineHealth(0f, 0f, 0, false)
    )
    val healthFlow: Flow<PipelineHealth> = _healthFlow.asStateFlow()

    private var currentFrameQueueSize = 0
    private var currentChunkQueueSize = 0

    fun updateFrameQueueSize(size: Int) {
        currentFrameQueueSize = size
        emitHealth()
    }

    fun updateChunkQueueSize(size: Int) {
        currentChunkQueueSize = size
        emitHealth()
    }

    fun shouldSkipAnalyser(): Boolean {
        return frameQueueUtilization() > 0.8f
    }

    fun shouldPauseChunking(): Boolean {
        return chunkQueueUtilization() > 0.8f
    }

    private fun frameQueueUtilization(): Float {
        if (frameChannelCapacity == 0) return 0f
        return currentFrameQueueSize.toFloat() / frameChannelCapacity
    }

    private fun chunkQueueUtilization(): Float {
        if (chunkChannelCapacity == 0) return 0f
        return currentChunkQueueSize.toFloat() / chunkChannelCapacity
    }

    private fun emitHealth() {
        val memoryMb = Runtime.getRuntime().let {
            (it.totalMemory() - it.freeMemory()) / (1024 * 1024)
        }
        val frameUtil = frameQueueUtilization()
        val chunkUtil = chunkQueueUtilization()

        _healthFlow.value = PipelineHealth(
            frameQueueUtilization = frameUtil,
            chunkQueueUtilization = chunkUtil,
            memoryUsageMb = memoryMb,
            isDegraded = frameUtil > 0.8f || chunkUtil > 0.8f
        )
    }
}
