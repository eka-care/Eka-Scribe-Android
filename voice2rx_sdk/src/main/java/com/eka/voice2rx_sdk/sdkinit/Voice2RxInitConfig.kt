package com.eka.voice2rx_sdk.sdkinit

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.sdkinit.ekaauth.EkaAuthConfig
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
    val authorizationToken: String,
    val ekaAuthConfig: EkaAuthConfig? = null,
)
