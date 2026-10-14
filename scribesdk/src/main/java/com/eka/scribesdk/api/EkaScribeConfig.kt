package com.eka.scribesdk.api

import com.eka.networking.client.NetworkConfig
import com.eka.scribesdk.encoder.AudioFormat

/**
 * @param clientId Mandatory client identifier for API authentication
 * @param flavour SDK flavour identifier sent as a header (default: "scribe-android")
 * @param enableAnalyser enable SQUIM audio quality analyser if true
 * @param debugMode enable detailed logging if true
 * @param networkConfig network and authentication configuration
 * @param useSessionsApi create new sessions on the EkaScribe Sessions API (`/voice/v1/sessions`)
 *        instead of the transaction APIs + S3 upload. Sessions already started keep their own flow.
 **/
data class EkaScribeConfig(
    val clientId: String,
    val flavour: String = "android",
    val enableAnalyser: Boolean = true,
    val debugMode: Boolean = false,
    val networkConfig: NetworkConfig,
    val useSessionsApi: Boolean = false,
) {
    companion object {
        internal const val SAMPLE_RATE = 16000
        internal const val FRAME_SIZE = 512
        internal const val PREFERRED_CHUNK_DURATION_SEC = 10
        internal const val DESPERATION_CHUNK_DURATION_SEC = 20
        internal const val MAX_CHUNK_DURATION_SEC = 25
        /** Sessions API discovery advertises max_chunk_duration_seconds = 20. */
        internal const val SESSIONS_API_MAX_CHUNK_DURATION_SEC = 20
        internal const val OVERLAP_DURATION_SEC = 0.5
        internal const val FULL_AUDIO_OUTPUT = false
        internal const val MAX_UPLOAD_RETRIES = 2
        internal const val POLL_MAX_RETRIES = 3
        internal const val POLL_DELAY_MS = 2000L
        internal val AUDIO_FORMAT = AudioFormat.MP3
    }
}
