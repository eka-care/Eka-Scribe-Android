package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class TemplateOutput(
    @SerializedName("name")
    val name: String?,
    @SerializedName("title")
    val title: String?,
    @SerializedName("sections")
    val sections: List<SectionData>,
    @SerializedName("sessionId")
    val sessionId: String,
    @SerializedName("templateId")
    val templateId: String? = null,
    @SerializedName("isEditable")
    val isEditable: Boolean = false,
    @SerializedName("type")
    val type: TemplateType
)

@Keep
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
