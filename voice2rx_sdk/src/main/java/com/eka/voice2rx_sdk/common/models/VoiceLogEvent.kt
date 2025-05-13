package com.eka.voice2rx_sdk.common.models

import org.json.JSONObject


data class VoiceLogEvent(
    val eventType: String,
    val eventTime: String,
    val sessionId: String,
    val eventData: JSONObject
)

enum class VoiceLogEventType {
    NETWORK_LOGS,
    CRASH_LOGS,
    RETRY_LOGS,
    JOURNEY_LOGS
}
