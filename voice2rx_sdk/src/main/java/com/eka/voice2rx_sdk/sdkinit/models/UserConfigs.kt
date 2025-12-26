package com.eka.voice2rx_sdk.sdkinit.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UserConfigs(
    @SerializedName("consultationModes")
    val consultationModes: ConsultationModeConfig,
    @SerializedName("supportedLanguages")
    val supportedLanguages: SupportedLanguagesConfig,
    @SerializedName("outputTemplates")
    val outputTemplates: OutputTemplatesConfig,
    @SerializedName("selectedUserPreferences")
    val selectedUserPreferences: SelectedUserPreferences,
    @SerializedName("modelConfigs")
    val modelConfigs: ModelConfigs,
)

@Keep
data class SelectedUserPreferences(
    @SerializedName("consultationMode")
    val consultationMode: ConsultationMode?,
    @SerializedName("languages")
    val languages: List<SupportedLanguage> = emptyList(),
    @SerializedName("output_templates")
    val outputTemplates: List<OutputTemplate> = emptyList(),
    @SerializedName("modelType")
    val modelType: ModelType? = null
)

@Keep
data class ModelConfigs(
    @SerializedName("modelTypes")
    val modelTypes: List<ModelType>,
    @SerializedName("maxSelection")
    val maxSelection: Int = 1
)

@Keep
data class ModelType(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("desc")
    val desc: String
)

@Keep
data class ConsultationModeConfig(
    @SerializedName("modes")
    val modes: List<ConsultationMode>,
    @SerializedName("maxSelection")
    val maxSelection: Int = 1,
)

@Keep
data class SupportedLanguagesConfig(
    @SerializedName("languages")
    val languages: List<SupportedLanguage>,
    @SerializedName("maxSelection")
    val maxSelection: Int = 1,
)

@Keep
data class OutputTemplatesConfig(
    @SerializedName("templates")
    val templates: List<OutputTemplate>,
    @SerializedName("maxSelection")
    val maxSelection: Int = 1,
)

@Keep
data class ConsultationMode(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String,
    @SerializedName("desc")
    val desc: String
)

@Keep
data class SupportedLanguage(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String
)

@Keep
data class OutputTemplate(
    @SerializedName("id")
    val id: String,
    @SerializedName("name")
    val name: String
)