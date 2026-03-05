package com.eka.scribesdk.api

import com.eka.networking.client.NetworkConfig

/**
 * @param sampleRate Audio sample rate in Hz
 * @param frameSize Audio frame size in samples
 * @param fullAudioOutput sends full audio file to server if true
 * @param preferredChunkDurationSec preferred chunk duration in seconds
 * @param desperationChunkDurationSec chunk duration in seconds if no speech is detected
 * @param maxChunkDurationSec maximum chunk duration in seconds
 * @param enableAnalyser enable analyser if true
 * @param overlapDurationSec overlap duration in seconds
 * @param debugMode enable debug mode if true
 * @param networkConfig network configuration
 * @param maxUploadRetries maximum number of retries for failed uploads
 * @param pollMaxRetries maximum number of times to poll for transcription result
 * @param pollDelayMs delay in milliseconds between each poll
 **/
data class EkaScribeConfig(
    val sampleRate: Int = 16000,
    val frameSize: Int = 512,
    val preferredChunkDurationSec: Int = 10,
    val desperationChunkDurationSec: Int = 20,
    val maxChunkDurationSec: Int = 25,
    val enableAnalyser: Boolean = true,
    val overlapDurationSec: Double = 0.5,
    val debugMode: Boolean = false,
    val networkConfig: NetworkConfig,
    val fullAudioOutput: Boolean = false,
    val maxUploadRetries: Int = 2,
    val pollMaxRetries: Int = 3,
    val pollDelayMs: Long = 2000L
)
