package com.eka.voice2rx_sdk.common

import com.eka.voice2rx_sdk.data.local.models.VoiceSessionData

sealed class ResponseState {
    object Loading : ResponseState()
    data class Success(val isCompleted : Boolean) : ResponseState()
    data class Error(val error : String) : ResponseState()
}

sealed class SessionResponse {
    data class Success(val data: VoiceSessionData) : SessionResponse()
    data class Error(val error: Exception) : SessionResponse()
}