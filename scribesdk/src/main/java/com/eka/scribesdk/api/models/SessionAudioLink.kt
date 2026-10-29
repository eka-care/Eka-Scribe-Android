package com.eka.scribesdk.api.models

/** A presigned download URL for a session's combined recording (MP3). Don't store it; it expires. */
data class SessionAudioLink(
    val url: String,
    /** Epoch millis when [url] stops working, if the server said. */
    val expiresAtMillis: Long?
)
