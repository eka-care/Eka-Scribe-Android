package com.eka.voice2rx_sdk.common.voicelogger

import android.content.Context
import com.eka.voice2rx_sdk.common.Voice2RxUtils.getNetworkCapabilities
import com.eka.voice2rx_sdk.sdkinit.Voice2Rx

fun logNetworkInfo(context: Context, sessionId: String? = null) {
    val data = getNetworkCapabilities(context = context)
    Voice2Rx.logEvent(
        EventLog.Info(
            code = EventCode.VOICE2RX_SESSION_UPLOAD_LIFECYCLE,
            params = mapOf(
                "sessionId" to sessionId,
                "networkinfo" to data.toString()
            )
        )
    )
}