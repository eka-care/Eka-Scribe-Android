package com.eka.scribesdk.api.models

import androidx.annotation.Keep
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
