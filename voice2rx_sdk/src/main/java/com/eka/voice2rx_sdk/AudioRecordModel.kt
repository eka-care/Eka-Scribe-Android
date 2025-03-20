package com.eka.voice2rx_sdk

import androidx.annotation.Keep

@Keep
data class AudioRecordModel(
    val frameData: ShortArray,
    val isSilence: Boolean,
    var isClipped: Boolean,
    var timeStamp: Long
)