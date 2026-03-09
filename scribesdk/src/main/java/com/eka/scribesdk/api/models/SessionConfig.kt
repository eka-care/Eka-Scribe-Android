package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class SessionConfig(
    @SerializedName("languages")
    val languages: List<String> = listOf("en-IN"),
    @SerializedName("mode")
    val mode: String = "dictation",
    @SerializedName("modelType")
    val modelType: String = "pro",
    @SerializedName("outputTemplates")
    val outputTemplates: List<OutputTemplate>? = null,
    @SerializedName("patientDetails")
    val patientDetails: PatientDetail? = null,
    @SerializedName("section")
    val section: String? = null,
    @SerializedName("speciality")
    val speciality: String? = null
)

@Keep
data class OutputTemplate(
    @SerializedName("templateId")
    val templateId: String,
    @SerializedName("templateType")
    val templateType: String = "custom",
    @SerializedName("templateName")
    val templateName: String?
)

@Keep
data class PatientDetail(
    @SerializedName("age")
    val age: Int? = null,
    @SerializedName("biologicalSex")
    val biologicalSex: String? = null,
    @SerializedName("name")
    val name: String? = null,
    @SerializedName("patientId")
    val patientId: String? = null,
    @SerializedName("visitId")
    val visitId: String? = null
)
