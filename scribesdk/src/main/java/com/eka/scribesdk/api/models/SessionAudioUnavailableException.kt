package com.eka.scribesdk.api.models

/**
 * Why the server recording is not available (see [com.eka.scribesdk.api.EkaScribe.getSessionAudioLink]
 * and [com.eka.scribesdk.api.EkaScribe.downloadSessionAudio]).
 */
class SessionAudioUnavailableException(
    val reason: Reason,
    message: String
) : Exception(message) {
    enum class Reason {
        /** The server reports the recording expired (410). */
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
