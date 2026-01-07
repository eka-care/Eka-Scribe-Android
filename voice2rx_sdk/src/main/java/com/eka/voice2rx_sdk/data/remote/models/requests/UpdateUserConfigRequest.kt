package com.eka.voice2rx_sdk.data.remote.models.requests


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UpdateUserConfigRequest(
    @SerializedName("data")
    var data: Data?,
    @SerializedName("request_type")
    var requestType: String = "user"
) {
    @Keep
    data class Data(
        @SerializedName("consultation_mode")
        var consultationMode: String?,
        @SerializedName("input_languages")
        var inputLanguages: List<InputLanguage?>?,
        @SerializedName("model_type")
        var modelType: String?,
        @SerializedName("output_format_template")
        var outputFormatTemplate: List<OutputFormatTemplate?>?
    ) {
        @Keep
        data class InputLanguage(
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?
        )

        @Keep
        data class OutputFormatTemplate(
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?,
            @SerializedName("template_type")
            var templateType: String?
        )
    }
}