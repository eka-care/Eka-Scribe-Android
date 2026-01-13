package com.eka.scribe_sdk.audio.models

enum class AudioSampleRate(val value: Int) {
    SAMPLE_RATE_8K(8000),
    SAMPLE_RATE_16K(16000);

    fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: SAMPLE_RATE_16K
}