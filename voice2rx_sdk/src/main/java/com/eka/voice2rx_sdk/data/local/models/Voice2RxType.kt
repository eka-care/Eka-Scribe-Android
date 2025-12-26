package com.eka.voice2rx_sdk.data.local.models

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType.CONSULTATION
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType.DICTATION
import com.google.gson.annotations.SerializedName

@Keep
enum class Voice2RxType(val value : String) {
    @SerializedName("consultation")
    CONSULTATION("consultation"),
    @SerializedName("dictation")
    DICTATION("dictation")
}

fun getVoice2RxType(name: String): Voice2RxType {
    return when (name) {
        CONSULTATION.value -> CONSULTATION
        DICTATION.value -> DICTATION
        else -> DICTATION
    }
}