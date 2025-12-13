package com.eka.scribe_sdk.audio.models

enum class AudioFrameSize(val value: Int) {
    FRAME_SIZE_256(256),
    FRAME_SIZE_512(512),
    FRAME_SIZE_768(768),
    FRAME_SIZE_1024(1024),
    FRAME_SIZE_1536(1536);

    fun fromValue(value: Int) = entries.firstOrNull { it.value == value } ?: FRAME_SIZE_512
}