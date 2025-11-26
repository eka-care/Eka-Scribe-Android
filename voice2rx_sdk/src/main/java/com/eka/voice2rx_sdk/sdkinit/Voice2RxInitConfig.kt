package com.eka.voice2rx_sdk.sdkinit

import androidx.annotation.Keep
import com.eka.networking.client.NetworkConfig
import com.eka.networking.token.TokenStorage
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.SampleRate

@Keep
data class Voice2RxInitConfig(
    val voice2RxLifecycle: Voice2RxLifecycleCallbacks,
    val sampleRate: SampleRate = SampleRate.SAMPLE_RATE_16K,
    val frameSize: FrameSize = FrameSize.FRAME_SIZE_512,
    val prefCutDuration: Int = 10, // In seconds
    val despCutDuration: Int = 20, // In seconds
    val maxCutDuration: Int = 25,
    val audioQuality: AudioQualityConfig = AudioQualityConfig.ENABLED,
    val audioQualityDuration: Int = 3, // In Seconds
    val networkConfig : NetworkConfig,
)

enum class AudioQualityConfig {
    ENABLED,
    DISABLED
}
