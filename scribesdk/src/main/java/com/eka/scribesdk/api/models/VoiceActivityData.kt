package com.eka.scribesdk.api.models

data class VoiceActivityData(
    val isSpeech: Boolean,
    val amplitude: Float,
    val timestampMs: Long
)
