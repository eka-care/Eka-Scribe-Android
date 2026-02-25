package com.eka.scribesdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class InitTransactionRequest(
    @SerializedName("input_language")
    val inputLanguage: List<String>?,
    @SerializedName("mode")
    val mode: String,
    @SerializedName("output_format_template")
    val outputFormatTemplate: List<OutputFormatTemplate>?,
    @SerializedName("s3_url")
    val s3Url: String?,
    @SerializedName("Section")
    val section: String? = null,
    @SerializedName("speciality")
    val speciality: String? = null,
    @SerializedName("transfer")
    val transfer: String = "vaded",
    @SerializedName("model_type")
    val modelType: String,
    @SerializedName("patient_details")
    val patientDetails: PatientDetails? = null
)

@Keep
data class PatientDetails(
    @SerializedName("age")
    val age: Int?,
    @SerializedName("biologicalSex")
    val biologicalSex: String?,
    @SerializedName("username")
    val name: String? = null,
    @SerializedName("oid")
    val patientId: String? = null,
    @SerializedName("visit_id")
    val visitId: String? = null
)

@Keep
data class OutputFormatTemplate(
    @SerializedName("template_id")
    val templateId: String?,
    @SerializedName("template_type")
    val type: String = "custom",
    @SerializedName("template_name")
    val name: String?
)
