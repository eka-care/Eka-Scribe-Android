package com.eka.scribesdk.recorder

data class AudioFrame(
    val pcm: ShortArray,
    val timestampMs: Long,
    val sampleRate: Int,
    val frameIndex: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return frameIndex == other.frameIndex && timestampMs == other.timestampMs
    }

    override fun hashCode(): Int {
        var result = frameIndex.hashCode()
        result = 31 * result + timestampMs.hashCode()
        return result
    }
}
