package com.eka.voice2rx_sdk.data.local.models

import androidx.annotation.Keep

@Keep
enum class Voice2RxType(val value : String) {
    CONSULTATION("consultation"),
    DICTATION("dictation")
}