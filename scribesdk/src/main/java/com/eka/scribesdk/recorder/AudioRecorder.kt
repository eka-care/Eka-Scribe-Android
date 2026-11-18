package com.eka.scribesdk.recorder

interface AudioRecorder {
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun setFrameCallback(callback: FrameCallback)
    fun setAudioFocusCallback(callback: AudioFocusCallback)

    /** Called with true when another app has taken the mic and we only get silence, false when it's back. */
    fun setMicSilencedCallback(callback: (Boolean) -> Unit) {}
}
