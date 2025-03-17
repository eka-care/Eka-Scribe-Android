package com.eka.voice2rx_sdk.recorder

interface AudioFocusListener {
    fun onAudioFocusGain()
    fun onAudioFocusGone()
}