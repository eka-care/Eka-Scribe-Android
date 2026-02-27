package com.eka.scribesdk.data.remote.models.responses

import android.util.Base64
import androidx.annotation.Keep
import com.eka.scribesdk.api.models.SectionData
import com.eka.scribesdk.api.models.SessionResult
import com.eka.scribesdk.api.models.TemplateOutput
import com.eka.scribesdk.api.models.TemplateType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

@Keep
internal data class ScribeResultResponse(
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
internal enum class ResultStatus {
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
internal enum class OutputType(val value: String) {
    @SerializedName("json")
    JSON("json"),

    @SerializedName("markdown")
    MARKDOWN("markdown"),

    @SerializedName("text")
    TEXT("text"),

    @SerializedName("custom")
    CUSTOM("custom")
}

private val SUCCESS_STATES = setOf(ResultStatus.SUCCESS, ResultStatus.PARTIAL_COMPLETED)

internal fun ScribeResultResponse.toSessionResult(sessionId: String): SessionResult {
    val outputs = mutableListOf<TemplateOutput>()

    data?.templateResults?.custom?.mapNotNull { output ->
        output?.toTemplateOutput(sessionId)
    }?.let { outputs.addAll(it) }

    data?.templateResults?.transcript?.mapNotNull { transcript ->
        transcript?.toTranscriptOutput(sessionId)
    }?.let { outputs.addAll(it) }

    return SessionResult(
        templates = outputs,
        audioQuality = data?.audioMatrix?.quality
    )
}

private fun ScribeResultResponse.Data.Output.toTemplateOutput(sessionId: String): TemplateOutput? {
    val data = value ?: return null
    val id = templateId ?: return null
    if (status !in SUCCESS_STATES) return null

    val sections = mutableListOf<SectionData>()
    var templateType = TemplateType.MARKDOWN

    if (type == OutputType.JSON) {
        templateType = TemplateType.JSON
        sections.addAll(decodeJsonSections(data))
    } else {
        sections.add(SectionData(title = name, value = decodeBase64(data)))
    }

    return TemplateOutput(
        name = name,
        title = name,
        sections = sections,
        sessionId = sessionId,
        templateId = id,
        isEditable = type == OutputType.JSON,
        type = templateType
    )
}

private fun ScribeResultResponse.Data.Transcript.toTranscriptOutput(sessionId: String): TemplateOutput? {
    val data = value ?: return null
    if (status !in SUCCESS_STATES) return null

    val name = "Transcript"
    val sections = mutableListOf<SectionData>()
    var templateType = TemplateType.MARKDOWN

    if (type == OutputType.JSON) {
        templateType = TemplateType.JSON
        sections.addAll(decodeJsonSections(data))
    } else {
        sections.add(SectionData(title = name, value = decodeBase64(data)))
    }

    return TemplateOutput(
        name = name,
        title = name,
        sections = sections,
        sessionId = sessionId,
        templateId = "transcript",
        type = templateType
    )
}

private fun decodeBase64(base64: String?): String {
    if (base64.isNullOrBlank()) return ""
    return try {
        String(Base64.decode(base64, Base64.DEFAULT))
    } catch (e: Exception) {
        ""
    }
}

private fun decodeJsonSections(base64: String?): List<SectionData> {
    val decoded = decodeBase64(base64)
    if (decoded.isBlank()) return emptyList()
    return try {
        Gson().fromJson(decoded, Array<SectionData>::class.java).toList()
    } catch (e: Exception) {
        emptyList()
    }
}
