package com.eka.voice2rx_sdk.data.local.models


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class FileInfo(
    @SerializedName("et")
    var et: String?,
    @SerializedName("st")
    var st: String?
)

enum class IncludeStatus {
    INCLUDED,
    NOT_INCLUDED,
}