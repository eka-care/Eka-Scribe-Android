package com.eka.voice2rx_sdk

import androidx.annotation.Keep

@Keep
data class AudioRecordModel(
    val frameData: ShortArray,
    val isSilence: Boolean,
    var isClipped: Boolean,
    var timeStamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioRecordModel

        if (isSilence != other.isSilence) return false
        if (isClipped != other.isClipped) return false
        if (timeStamp != other.timeStamp) return false
        if (!frameData.contentEquals(other.frameData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isSilence.hashCode()
        result = 31 * result + isClipped.hashCode()
        result = 31 * result + timeStamp.hashCode()
        result = 31 * result + frameData.contentHashCode()
        return result
    }
}