package com.eka.voice2rx_sdk.data.remote.models.responses

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.requests.AdditionalData
import com.google.gson.annotations.SerializedName

@Keep
data class EkaScribeResultV3(
    @SerializedName("data")
    var data: Data?
) {
    @Keep
    data class Data(
        @SerializedName("additional_data")
        var additionalData: AdditionalData?,
        @SerializedName("audio_matrix")
        var audioMatrix: AudioMatrix?,
        @SerializedName("created_at")
        var createdAt: String?,
        @SerializedName("output")
        var output: List<Output?>?,
        @SerializedName("template_results")
        var templateResults: TemplateResults?
    ) {
        @Keep
        data class AudioMatrix(
            @SerializedName("quality")
            var quality: Double?
        )

        @Keep
        data class Output(
            @SerializedName("errors")
            var errors: List<TemplateResults.Error?>?,
            @SerializedName("name")
            var name: String?,
            @SerializedName("status")
            var status: Voice2RxStatus?,
            @SerializedName("template_id")
            var templateId: String?,
            @SerializedName("type")
            var type: OutputType?,
            @SerializedName("value")
            var value: String?,
            @SerializedName("warnings")
            var warnings: List<TemplateResults.Warning?>?
        )

        @Keep
        data class TemplateResults(
            @SerializedName("custom")
            var custom: List<Custom?>?,
            @SerializedName("integration")
            var integration: List<Any?>?,
            @SerializedName("transcript")
            var transcript: List<Transcript?>?
        ) {
            @Keep
            data class Custom(
                @SerializedName("errors")
                var errors: List<TemplateResults.Error?>?,
                @SerializedName("name")
                var name: String?,
                @SerializedName("status")
                var status: Voice2RxStatus?,
                @SerializedName("template_id")
                var templateId: String?,
                @SerializedName("type")
                var type: OutputType?,
                @SerializedName("value")
                var value: String?,
                @SerializedName("warnings")
                var warnings: List<TemplateResults.Warning?>?
            )

            @Keep
            data class Transcript(
                @SerializedName("errors")
                var errors: List<TemplateResults.Error?>?,
                @SerializedName("lang")
                var lang: String?,
                @SerializedName("status")
                var status: Voice2RxStatus?,
                @SerializedName("type")
                var type: OutputType?,
                @SerializedName("value")
                var value: String?,
                @SerializedName("warnings")
                var warnings: List<TemplateResults.Warning?>?
            )

            @Keep
            data class Error(
                @SerializedName("code")
                var code: String?,
                @SerializedName("msg")
                var msg: String?,
                @SerializedName("type")
                var type: String?
            )

            @Keep
            data class Warning(
                @SerializedName("code")
                var code: String?,
                @SerializedName("msg")
                var msg: String?,
                @SerializedName("type")
                var type: String?
            )
        }
    }
}