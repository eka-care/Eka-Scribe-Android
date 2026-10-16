package com.eka.scribesdk.api

import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionEvent
import com.eka.scribesdk.api.models.SessionResult

interface EkaScribeCallback {
    fun onSessionStarted(sessionId: String)
    fun onSessionPaused(sessionId: String)
    fun onSessionResumed(sessionId: String)
    fun onSessionStopped(sessionId: String, chunkCount: Int)
    fun onError(error: ScribeError)
    fun onTranscriptReady(sessionId: String, result: SessionResult) {}
    fun onOutputReady(sessionId: String, result: SessionResult) {}
    fun onSessionCompleted(sessionId: String, result: SessionResult) {}
    fun onSessionFailed(sessionId: String, error: ScribeError) {}
    fun onSessionCancelled(sessionId: String) {}
    fun onAudioFocusChanged(hasFocus: Boolean) {}

    /**
     * Recorded time (pauses excluded) reached the model's max session duration (Sessions API
     * discovery: lite 10 min, pro 60 min). Stop the session through your normal stop path here so
     * the UI moves on; the SDK stops it right after this call if it is still recording.
     */
    fun onSessionDurationLimitReached(sessionId: String, limitSeconds: Int) {}
    fun onSessionEvent(event: SessionEvent) {}
}
