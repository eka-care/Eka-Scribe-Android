package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.remote.models.requests.AdditionalData
import com.google.gson.annotations.SerializedName

@Keep
data class EkaScribeResult(
    @SerializedName("data")
    var data: Data?
) {
    @Keep
    data class Data(
        @SerializedName("additional_data")
        var additionalData: AdditionalData?,
        @SerializedName("output")
        var output: List<Output?>?
    ) {
        @Keep
        data class Output(
            @SerializedName("errors")
            var errors: List<Error?>?,
            @SerializedName("name")
            var name: String?,
            @SerializedName("status")
            var status: Voice2RxStatus? = Voice2RxStatus.IN_PROGRESS,
            @SerializedName("template_id")
            var templateId: String?,
            @SerializedName("type")
            var type: OutputType?,
            @SerializedName("value")
            var value: String?,
            @SerializedName("warnings")
            var warnings: List<Warning?>?
        ) {
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