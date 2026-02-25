package com.eka.scribesdk.recorder

fun interface FrameCallback {
    fun onFrame(frame: AudioFrame)
}
