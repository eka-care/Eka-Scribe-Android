package com.eka.scribesdk.recorder

data class RecorderConfig(
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val encoding: Int = android.media.AudioFormat.ENCODING_PCM_16BIT,
    val frameSize: Int = 512
)
