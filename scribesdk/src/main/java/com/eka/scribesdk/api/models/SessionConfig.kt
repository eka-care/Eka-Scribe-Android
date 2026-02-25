package com.eka.scribesdk.api.models

data class SessionConfig(
    val languages: List<String> = listOf("en-IN"),
    val mode: String = "dictation",
    val modelType: String = "pro",
    val outputTemplates: List<OutputTemplate>? = null,
    val patientDetails: PatientDetail? = null,
    val section: String? = null,
    val speciality: String? = null
)

data class OutputTemplate(
    val templateId: String,
    val templateType: String = "custom",
    val templateName: String?
)

data class PatientDetail(
    val age: Int? = null,
    val biologicalSex: String? = null,
    val name: String? = null,
    val patientId: String? = null,
    val visitId: String? = null
)
