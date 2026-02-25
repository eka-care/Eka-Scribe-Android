package com.eka.scribesdk.pipeline

interface PipelineStage<I, O> {
    suspend fun process(input: I): O
    fun start()
    fun stop()
}
