package com.eka.voice2rx_sdk.sdkinit

import com.eka.voice2rx_sdk.common.models.VoiceError

interface Voice2RxLifecycleCallbacks {
    fun onStartSession(sessionId: String)
    fun onStopSession(sessionId: String, recordedFiles: Int)
    fun onPauseSession(sessionId: String)
    fun onResumeSession(sessionId: String)
    fun onError(sessionId: String, error: VoiceError)
}