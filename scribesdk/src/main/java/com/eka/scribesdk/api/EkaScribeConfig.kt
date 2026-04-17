package com.eka.scribesdk.api

import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.encoder.AudioFormat

/**
 * @param clientId Mandatory client identifier for API authentication
 * @param flavour SDK flavour identifier sent as a header (default: "scribe-android")
 * @param enableAnalyser enable SQUIM audio quality analyser if true
 * @param debugMode enable detailed logging if true
 * @param networkConfig network and authentication configuration
 **/
data class EkaScribeConfig(
    val clientId: String,
    val flavour: String = "android",
    val enableAnalyser: Boolean = true,
    val debugMode: Boolean = false,
    val networkConfig: NetworkConfig,
) {
    companion object {
        internal const val SAMPLE_RATE = 16000
        internal const val FRAME_SIZE = 512
        internal const val PREFERRED_CHUNK_DURATION_SEC = 10
        internal const val DESPERATION_CHUNK_DURATION_SEC = 20
        internal const val MAX_CHUNK_DURATION_SEC = 25
        internal const val OVERLAP_DURATION_SEC = 0.5
        internal const val FULL_AUDIO_OUTPUT = false
        internal const val MAX_UPLOAD_RETRIES = 2
        internal const val POLL_MAX_RETRIES = 3
        internal const val POLL_DELAY_MS = 2000L
        internal val AUDIO_FORMAT = AudioFormat.MP3
    }
}
