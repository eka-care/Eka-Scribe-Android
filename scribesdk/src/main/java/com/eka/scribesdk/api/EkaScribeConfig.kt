package com.eka.scribesdk.api

import com.eka.networking.client.NetworkConfig

data class EkaScribeConfig(
    val sampleRate: Int = 16000,
    val frameSize: Int = 512,
    val preferredChunkDurationSec: Int = 10,
    val desperationChunkDurationSec: Int = 20,
    val maxChunkDurationSec: Int = 25,
    val enableAnalyser: Boolean = true,
    val debugMode: Boolean = false,
    val networkConfig: NetworkConfig,
    val maxUploadRetries: Int = 2
)
