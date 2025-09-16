package com.eka.voice2rx_sdk.data.remote.models

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeResult
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStatus

@Keep
data class SessionStatus(
    val sessionId: String,
    val status: Voice2RxStatus? = null,
    val ekaScribeResult: List<EkaScribeResult.Data.Output?>? = null,
    val error: Error? = null
)