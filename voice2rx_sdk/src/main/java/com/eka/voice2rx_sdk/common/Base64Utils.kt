package com.eka.voice2rx_sdk.common

import android.util.Base64
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import java.nio.charset.StandardCharsets

object Base64Utils {
    fun decodeBase64(base64: String?): String {
        try {
            if (base64 == null) return ""
            return String(
                Base64.decode(
                    base64,
                    Base64.DEFAULT
                ),
                StandardCharsets.UTF_8
            )
        } catch (e: Exception) {
            VoiceLogger.e("BaseUtil", "decodeBase64 : $base64", e)
            return ""
        }
    }

    fun encodeToBase64(data: String?): String {
        try {
            if (data == null) return ""
            return Base64.encodeToString(data.toByteArray(), Base64.DEFAULT)
        } catch (e: Exception) {
            VoiceLogger.e("BaseUtil", "decodeBase64 : $data", e)
            return ""
        }
    }
}