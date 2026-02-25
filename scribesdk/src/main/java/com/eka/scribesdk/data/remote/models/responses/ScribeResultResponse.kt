package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ScribeResultResponse(
    @SerializedName("data")
    val data: Data?
) {
    @Keep
    data class Data(
        @SerializedName("audio_matrix")
        val audioMatrix: AudioMatrix?,
        @SerializedName("created_at")
        val createdAt: String?,
        @SerializedName("output")
        val output: List<Output?>?,
        @SerializedName("template_results")
        val templateResults: TemplateResults?
    ) {
        @Keep
        data class AudioMatrix(
            @SerializedName("quality")
            val quality: Double?
        )

        @Keep
        data class Output(
            @SerializedName("errors")
            val errors: List<ResultError?>?,
            @SerializedName("name")
            val name: String?,
            @SerializedName("status")
            val status: ResultStatus?,
            @SerializedName("template_id")
            val templateId: String?,
            @SerializedName("type")
            val type: OutputType?,
            @SerializedName("value")
            val value: String?,
            @SerializedName("warnings")
            val warnings: List<ResultWarning?>?
        )

        @Keep
        data class TemplateResults(
            @SerializedName("custom")
            val custom: List<Output?>?,
            @SerializedName("integration")
            val integration: List<Any?>?,
            @SerializedName("transcript")
            val transcript: List<Transcript?>?
        )

        @Keep
        data class Transcript(
            @SerializedName("errors")
            val errors: List<ResultError?>?,
            @SerializedName("lang")
            val lang: String?,
            @SerializedName("status")
            val status: ResultStatus?,
            @SerializedName("type")
            val type: OutputType?,
            @SerializedName("value")
            val value: String?,
            @SerializedName("warnings")
            val warnings: List<ResultWarning?>?
        )

        @Keep
        data class ResultError(
            @SerializedName("code")
            val code: String?,
            @SerializedName("msg")
            val msg: String?,
            @SerializedName("type")
            val type: String?
        )

        @Keep
        data class ResultWarning(
            @SerializedName("code")
            val code: String?,
            @SerializedName("msg")
            val msg: String?,
            @SerializedName("type")
            val type: String?
        )
    }
}

@Keep
enum class ResultStatus {
    @SerializedName("in-progress")
    IN_PROGRESS,

    @SerializedName("success")
    SUCCESS,

    @SerializedName("failure")
    FAILURE,

    @SerializedName("partial_success")
    PARTIAL_COMPLETED
}

@Keep
enum class OutputType(val value: String) {
    @SerializedName("json")
    JSON("json"),

    @SerializedName("markdown")
    MARKDOWN("markdown"),

    @SerializedName("text")
    TEXT("text"),

    @SerializedName("custom")
    CUSTOM("custom")
}
