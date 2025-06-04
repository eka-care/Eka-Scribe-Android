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

//{ id: 'en-IN', name: 'English (India)' },
//{ id: 'en-US', name: 'English (United States)' },
//{ id: 'hi', name: 'Hindi' },
//{ id: 'gu', name: 'Gujarati' },
//{ id: 'kn', name: 'Kannada' },
//{ id: 'ml', name: 'Malayalam' },
//{ id: 'ta', name: 'Tamil' },
//{ id: 'te', name: 'Telugu' },
//{ id: 'bn', name: 'Bengali' },
//{ id: 'mr', name: 'Marathi' },
//{ id: 'pa', name: 'Punjabi' },

@Keep
enum class SupportedLanguages(val value: String) {
    // English (India)
    @SerializedName("en-IN")
    EN_IN("en-IN"),

    // English (United States)
    @SerializedName("en-US")
    EN_US("en-US"),

    // Hindi
    @SerializedName("hi")
    HI_IN("hi"),

    // Gujarati
    @SerializedName("gu")
    GU_IN("gu"),

    // Kannada
    @SerializedName("kn")
    KN_IN("kn"),

    // Malayalam
    @SerializedName("ml")
    ML_IN("ml"),

    // Tamil
    @SerializedName("ta")
    TA_IN("ta"),

    // Telugu
    @SerializedName("te")
    TE_IN("te"),

    // Bengali
    @SerializedName("bn")
    BN_IN("bn"),

    // Marathi
    @SerializedName("mr")
    MR_IN("mr"),

    // Punjabi
    @SerializedName("pa")
    PA_IN("pa");

    companion object {
        fun fromValue(value: String): SupportedLanguages? {
            return SupportedLanguages.entries.find { it.value == value }
        }
    }
}