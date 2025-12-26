package com.eka.voice2rx_sdk.sdkinit.models

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.common.Base64Utils
import com.eka.voice2rx_sdk.common.Voice2RxUtils
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeResultV3
import com.eka.voice2rx_sdk.data.remote.models.responses.OutputType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

@Keep
data class TemplateOutput(
    val name: String?,
    val title: String?,
    val sections: List<SectionData>,
    val sessionId: String,
    val templateId: String? = null,
    val isEditable: Boolean = false,
    val type: TemplateType
)

enum class TemplateType {
    MARKDOWN,
    JSON
}

@Keep
data class SectionData(
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("value")
    val value: String? = null
)

fun EkaScribeResultV3.Data.TemplateResults.Custom.toTemplateOutput(sessionId: String): TemplateOutput? {
    val data = value ?: return null
    val id = templateId ?: return null
    val successStates = Voice2RxUtils.getOutputSuccessStates()
    if (status !in successStates) {
        return null
    }
    val templateValue = mutableListOf<SectionData>()
    var templateType = TemplateType.MARKDOWN
    if (type == OutputType.JSON) {
        templateType = TemplateType.JSON
        templateValue.addAll(extractData(data))
    } else {
        templateValue.add(
            SectionData(
                title = name,
                value = Base64Utils.decodeBase64(base64 = data)
            )
        )
    }
    return TemplateOutput(
        name = name,
        title = name,
        sections = templateValue,
        sessionId = sessionId,
        templateId = id,
        isEditable = type == OutputType.JSON,
        type = templateType
    )
}

fun EkaScribeResultV3.Data.TemplateResults.Transcript.toTemplateOutput(sessionId: String): TemplateOutput? {
    val data = value ?: return null
    val successStates = Voice2RxUtils.getOutputSuccessStates()
    if (status !in successStates) {
        return null
    }
    val name = "Transcript"
    val templateValue = mutableListOf<SectionData>()
    var templateType = TemplateType.MARKDOWN
    if (type == OutputType.JSON) {
        templateType = TemplateType.JSON
        templateValue.addAll(extractData(data))
    } else {
        templateValue.add(
            SectionData(
                title = name,
                value = Base64Utils.decodeBase64(base64 = data)
            )
        )
    }
    return TemplateOutput(
        name = name,
        title = name,
        sections = templateValue,
        sessionId = sessionId,
        templateId = "transcript",
        type = templateType
    )
}

fun extractData(base64: String?): List<SectionData> {
    val data = Base64Utils.decodeBase64(base64 = base64)
    if (data.isBlank()) return listOf()
    try {
        val result = Gson().fromJson(data, Array<SectionData>::class.java)
        return result.toList()
    } catch (e: Exception) {
        return listOf()
    }
}