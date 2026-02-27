package com.eka.scribesdk.api

import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionResult

interface EkaScribeCallback {
    fun onSessionStarted(sessionId: String)
    fun onSessionPaused(sessionId: String)
    fun onSessionResumed(sessionId: String)
    fun onSessionStopped(sessionId: String, chunkCount: Int)
    fun onError(error: ScribeError)
    fun onSessionCompleted(sessionId: String, result: SessionResult) {}
    fun onSessionFailed(sessionId: String, error: ScribeError) {}
}
