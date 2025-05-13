package com.eka.voice2rx_sdk.data.local.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
enum class Voice2RxType(val value : String) {
    @SerializedName("consultation")
    CONSULTATION("consultation"),
    @SerializedName("dictation")
    DICTATION("dictation")
}