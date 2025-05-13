package com.eka.voice2rx_sdk.common.voicelogger

import org.json.JSONObject

sealed class EventLog {
    data class Error(
        val message: String,
        val code: EventCode?
    ) : EventLog()

    data class Info(
        val params: JSONObject = JSONObject(),
        val code: EventCode? = null
    ) : EventLog()

    data class Warning(
        val message: String,
        val warningCode: EventCode? = null
    ) : EventLog()
}

enum class EventCode(val value: String) {
    VOICE2RX_SESSION_LIFECYCLE("voice2rx_session_lifecycle"),
    VOICE2RX_SESSION_UPLOAD_LIFECYCLE("voice2rx_session_upload_lifecycle"),
    VOICE2RX_SESSION_STATUS("voice2rx_session_status"),
    VOICE2RX_SESSION_ERROR("voice2rx_error"),
    VOICE2RX_SESSION_WARNING("voice2rx_warning"),
}