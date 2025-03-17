package com.eka.voice2rx_sdk.sdkinit

import androidx.annotation.Keep
import com.eka.network.IOkHttpSetup
import com.eka.voice2rx_sdk.common.models.VoiceError
import com.eka.voice2rx_sdk.data.local.models.ContextData
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.SampleRate

@Keep
data class Voice2RxInitConfig(
    val onStart: (sessionId: String) -> Unit,
    val onStop: (sessionId: String, recordedFiles : Int) -> Unit,
    val onPaused: (sessionId: String) -> Unit,
    val onResumed: (sessionId: String) -> Unit,
    val onError : (sessionId : String, error : VoiceError) -> Unit,
    val docOid: String,
    val docUuid: String,
    val ownerId : String,
    val callerId : String,
    val contextData: ContextData,
    val sampleRate: SampleRate = SampleRate.SAMPLE_RATE_16K,
    val frameSize: FrameSize = FrameSize.FRAME_SIZE_512,
    val prefCutDuration: Int = 10,
    val despCutDuration: Int = 20,
    val maxCutDuration: Int = 25,
    val okHttpSetup: IOkHttpSetup,
)
