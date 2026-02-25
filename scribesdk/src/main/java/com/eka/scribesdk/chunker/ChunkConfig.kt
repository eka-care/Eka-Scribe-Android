package com.eka.scribesdk.chunker

data class ChunkConfig(
    val preferredDurationMs: Long = 10_000L,
    val desperationDurationMs: Long = 20_000L,
    val maxDurationMs: Long = 25_000L,
    val minSilenceToChunkMs: Long = 500L,
    val despSilenceToChunkMs: Long = 100L
)
