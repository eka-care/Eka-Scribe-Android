package com.eka.scribesdk.pipeline

/**
 * Evaluates pipeline health and returns an adjusted [PipelineConfig]
 * to gracefully degrade under load.
 */
class DegradationPolicy(private val baseConfig: PipelineConfig) {

    fun evaluate(health: PipelineHealth): PipelineConfig {
        var config = baseConfig

        // Skip analyser when frame queue is overloaded
        if (health.frameQueueUtilization > 0.8f) {
            config = config.copy(enableAnalyser = false)
        }

        return config
    }
}
