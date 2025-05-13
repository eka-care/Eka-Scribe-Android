package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxInitTransactionRequest(
    @SerializedName("additional_data")
    var additionalData: AdditionalData?,
    @SerializedName("input_language")
    var inputLanguage: List<String?>?,
    @SerializedName("mode")
    var mode: Voice2RxType = Voice2RxType.DICTATION,
    @SerializedName("output_format_template")
    var outputFormatTemplate: List<OutputFormatTemplate?>?,
    @SerializedName("s3_url")
    var s3Url: String?,
    @SerializedName("Section")
    var section: String?,
    @SerializedName("speciality")
    var speciality: String?,
    @SerializedName("transfer")
    var transfer: String = "vaded"
)

@Keep
enum class SupportedLanguages(val value: String) {
    // English (India)
    @SerializedName("en-IN")
    EN_IN("en-IN"),

    // English (United States)
    @SerializedName("en-US")
    EN_US("en-US"),

    // Hindi
    @SerializedName("hi-IN")
    HI_IN("hi-IN"),

    // Gujarati
    @SerializedName("gu-IN")
    GU_IN("gu-IN"),

    // Kannada
    @SerializedName("kn-IN")
    KN_IN("kn-IN");

    companion object {
        fun fromValue(value: String): SupportedLanguages? {
            return SupportedLanguages.entries.find { it.value == value }
        }
    }
}