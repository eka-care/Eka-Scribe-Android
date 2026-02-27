package com.eka.scribesdk.recorder

interface AudioRecorder {
    fun start()
    fun stop()
    fun pause()
    fun resume()
    fun setFrameCallback(callback: FrameCallback)
    fun setAudioFocusCallback(callback: AudioFocusCallback)
}
