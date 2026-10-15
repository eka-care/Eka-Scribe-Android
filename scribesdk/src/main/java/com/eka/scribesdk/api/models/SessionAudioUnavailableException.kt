package com.eka.scribesdk.api.models

/** Why [com.eka.scribesdk.api.EkaScribe.downloadSessionAudio] could not return the recording. */
class SessionAudioUnavailableException(
    val reason: Reason,
    message: String
) : Exception(message) {
    enum class Reason {
        /** Older than the 24-hour retention; no network call is made. */
        EXPIRED,

        /** The server is still combining the audio after End Session; retry shortly. */
        NOT_READY,

        /** Full-audio access is not enabled for this business (403 `audio_not_enabled`). */
        NOT_ENABLED,

        /** Unknown session, or the server no longer has the recording. */
        NOT_FOUND,

        /** The download itself failed. */
        FAILED
    }
}
