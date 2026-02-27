package com.eka.scribesdk.chunker

data class ChunkConfig(
    val preferredDurationSec: Int = 10,
    val desperationDurationSec: Int = 20,
    val maxDurationSec: Int = 25,
    val longSilenceSec: Double = 0.5,
    val shortSilenceSec: Double = 0.1
)
