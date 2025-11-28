package com.eka.scribe_sdk.audio.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class AudioData(
    @SerializedName("frame_data")
    val frameData: List<Short>,
    @SerializedName("timestamp")
    val timestamp: Long, // UTC in millis
    @SerializedName("sample_rate")
    val sampleRate: AudioSampleRate,
    @SerializedName("frame_size")
    val frameSize: AudioFrameSize
)
