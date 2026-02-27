package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class UserConfigs(
    val consultationModes: ConsultationModeConfig,
    val supportedLanguages: SupportedLanguagesConfig,
    val outputTemplates: OutputTemplatesConfig,
    val selectedUserPreferences: SelectedUserPreferences,
    val modelConfigs: ModelConfigs,
)

@Keep
data class ConsultationModeConfig(
    val modes: List<ConsultationMode>,
    val maxSelection: Int = 1,
)

@Keep
data class SupportedLanguagesConfig(
    val languages: List<SupportedLanguage>,
    val maxSelection: Int = 1,
)

@Keep
data class OutputTemplatesConfig(
    val templates: List<ConfigOutputTemplate>,
    val maxSelection: Int = 1,
)

@Keep
data class SelectedUserPreferences(
    @SerializedName("consultationMode")
    val consultationMode: ConsultationMode?,
    @SerializedName("languages")
    val languages: List<SupportedLanguage> = emptyList(),
    @SerializedName("output_templates")
    val outputTemplates: List<ConfigOutputTemplate> = emptyList(),
    @SerializedName("modelType")
    val modelType: ModelType? = null
)

@Keep
data class ModelConfigs(
    val modelTypes: List<ModelType>,
    val maxSelection: Int = 1
)

@Keep
data class ModelType(
    val id: String,
    val name: String,
    val desc: String
)

@Keep
data class ConsultationMode(
    val id: String,
    val name: String,
    val desc: String
)

@Keep
data class SupportedLanguage(
    val id: String,
    val name: String
)

@Keep
data class ConfigOutputTemplate(
    val id: String,
    val name: String
)
