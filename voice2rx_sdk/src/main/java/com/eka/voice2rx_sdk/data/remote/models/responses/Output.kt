package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Output(
    @SerializedName("name")
    val name: String?,
    @SerializedName("template_id")
    val templateId: TemplateId?,
    @SerializedName("type")
    val type: OutputType?,
    @SerializedName("value")
    val value: String?
)

@Keep
enum class TemplateId(val value: String) {
    @SerializedName("eka_emr_template")
    EKA_EMR_TEMPLATE("eka_emr_template"),
    @SerializedName("clinical_notes_template")
    CLINICAL_NOTE_TEMPLATE("clinical_notes_template"),
    @SerializedName("transcript_template")
    TRANSCRIPT_TEMPLATE("transcript_template"),
    @SerializedName("eka_emr_to_fhir_template")
    EKA_EMR_TO_FHIR_TEMPLATE("eka_emr_to_fhir_template"),
    @SerializedName("nic_template")
    NIC_TEMPLATE("nic_template")
}

@Keep
enum class OutputType(val value: String) {
    @SerializedName("json")
    JSON("json"),

    @SerializedName("markdown")
    MARKDOWN("markdown"),

    @SerializedName("text")
    TEXT("text")
}

//"data": {
//    "output": [
//    {
//        "template_id": "eka_emr_template",
//        "value": "<base 64 encoded>",
//        "type": "json",
//        "name": "Eka EMR Format"
//    },
//    {
//        "template_id": "clinical_note_template",
//        "value": "<base 64 encoded>",
//        "type": "markdown",
//        "name": "Clinical Notes"
//    },
//    {
//        "template_id": "transcript_template",
//        "value": "<base 64 encoded>",
//        "type": "text",
//        "name": "Transcription"
//    },
//    {
//        "template_id": "eka_emr_to_fhir_template",
//        "value": "<base 64 encoded>",
//        "type": "json",
//        "name": "Parchi to FHIR"
//    },
//    {
//        "template_id": "nic_template",
//        "value": "<base 64 encoded>",
//        "type": "json",
//        "name": "NIC Template"
//    }
//    ]
//}