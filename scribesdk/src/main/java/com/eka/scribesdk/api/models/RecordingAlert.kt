package com.eka.scribesdk.api.models

/**
 * Something the doctor should know about the recording right now. Shown on the recording
 * notification by the SDK; the app can show it too ([com.eka.scribesdk.api.EkaScribe.getRecordingAlert]).
 */
enum class RecordingAlert {
    /** Another app took the mic. The SDK paused the session: it was only recording silence. */
    MIC_IN_USE,

    /** Long recording: "Still recording?". Answer with confirmStillRecording(), or end the session. */
    STILL_RECORDING,

    /** "Still recording?" went unanswered, so the SDK paused the session. */
    PAUSED_NO_RESPONSE,

    /** Battery is low and not charging: end soon so the note isn't lost. */
    LOW_BATTERY,
}
