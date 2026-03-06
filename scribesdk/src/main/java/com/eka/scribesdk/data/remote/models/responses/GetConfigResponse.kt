package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.eka.scribesdk.api.models.ConfigOutputTemplate
import com.eka.scribesdk.api.models.ConsultationMode
import com.eka.scribesdk.api.models.ConsultationModeConfig
import com.eka.scribesdk.api.models.ModelConfigs
import com.eka.scribesdk.api.models.ModelType
import com.eka.scribesdk.api.models.OutputTemplatesConfig
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SupportedLanguage
import com.eka.scribesdk.api.models.SupportedLanguagesConfig
import com.eka.scribesdk.api.models.UserConfigs
import com.google.gson.annotations.SerializedName

@Keep
internal data class GetConfigResponse(
    @SerializedName("data")
    var data: Data?
) {
    @Keep
    data class Data(
        @SerializedName("consultation_modes")
        var consultationModes: List<ConsultationModeItem?>?,
        @SerializedName("max_selection")
        var maxSelection: MaxSelection?,
        @SerializedName("my_templates")
        var myTemplates: List<MyTemplate?>?,
        @SerializedName("selected_preferences")
        var selectedPreferences: SelectedPreferences?,
        @SerializedName("settings")
        var settings: Settings?,
        @SerializedName("supported_languages")
        var supportedLanguages: List<SupportedLanguageItem?>?,
        @SerializedName("supported_output_formats")
        var supportedOutputFormats: List<SupportedOutputFormat?>?,
    ) {
        @Keep
        data class ConsultationModeItem(
            @SerializedName("desc")
            var desc: String?,
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?
        )

        @Keep
        data class MaxSelection(
            @SerializedName("consultation_modes")
            var consultationModes: Int?,
            @SerializedName("supported_languages")
            var supportedLanguages: Int?,
            @SerializedName("supported_output_formats")
            var supportedOutputFormats: Int?
        )

        @Keep
        data class MyTemplate(
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?
        )

        @Keep
        data class SelectedPreferences(
            @SerializedName("auto_download")
            var autoDownload: Boolean?,
            @SerializedName("consultation_mode")
            var consultationModeId: String?,
            @SerializedName("languages")
            var languages: List<SupportedLanguageItem?>?,
            @SerializedName("model_type")
            var modelType: String?,
            @SerializedName("output_formats")
            var outputFormats: List<MyTemplate?>?
        )

        @Keep
        data class Settings(
            @SerializedName("model_training_consent")
            var modelTrainingConsent: ModelTrainingConsent?
        ) {
            @Keep
            data class ModelTrainingConsent(
                @SerializedName("editable")
                var editable: Boolean?,
                @SerializedName("value")
                var value: Boolean?
            )
        }

        @Keep
        data class SupportedLanguageItem(
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?
        )

        @Keep
        data class SupportedOutputFormat(
            @SerializedName("id")
            var id: String?,
            @SerializedName("name")
            var name: String?
        )
    }
}

private val MODEL_TYPES = listOf(
    ModelType(
        id = "lite",
        name = "Lite",
        desc = "Lightweight, faster results with balanced accuracy."
    ),
    ModelType(
        id = "pro",
        name = "Pro",
        desc = "Our best model built for accuracy, may take a little longer."
    ),
)

internal fun GetConfigResponse.Data.ConsultationModeItem.toConsultationMode(): ConsultationMode? {
    return id?.let { ConsultationMode(id = it, name = name ?: "", desc = desc ?: "") }
}

internal fun GetConfigResponse.Data.MyTemplate.toOutputFormat(): ConfigOutputTemplate? {
    return id?.let { ConfigOutputTemplate(id = it, name = name ?: "") }
}

internal fun GetConfigResponse.Data.SupportedLanguageItem.toSupportedLanguage(): SupportedLanguage? {
    return id?.let { SupportedLanguage(id = it, name = name ?: "") }
}

internal fun GetConfigResponse.Data.toUserConfigs(): UserConfigs {
    val languages = mutableListOf<SupportedLanguage>()
    val modes = mutableListOf<ConsultationMode>()
    val outputFormats = mutableListOf<ConfigOutputTemplate>()

    this.consultationModes?.mapNotNull { it?.toConsultationMode() }
        ?.let { modes.addAll(it) }
    this.myTemplates?.mapNotNull { it?.toOutputFormat() }
        ?.let { outputFormats.addAll(it) }
    this.supportedLanguages?.mapNotNull { it?.toSupportedLanguage() }
        ?.let { languages.addAll(it) }

    return UserConfigs(
        consultationModes = ConsultationModeConfig(
            modes = modes,
            maxSelection = this.maxSelection?.consultationModes ?: 1
        ),
        supportedLanguages = SupportedLanguagesConfig(
            languages = languages,
            maxSelection = this.maxSelection?.supportedLanguages ?: 1
        ),
        outputTemplates = OutputTemplatesConfig(
            templates = outputFormats,
            maxSelection = this.maxSelection?.supportedOutputFormats ?: 1
        ),
        selectedUserPreferences = SelectedUserPreferences(
            consultationMode = modes.firstOrNull { it.id == this.selectedPreferences?.consultationModeId },
            languages = this.selectedPreferences?.languages?.mapNotNull { it?.toSupportedLanguage() }
                ?: emptyList(),
            outputTemplates = this.selectedPreferences?.outputFormats?.mapNotNull { it?.toOutputFormat() }
                ?: emptyList(),
            modelType = MODEL_TYPES.firstOrNull { it.id == this.selectedPreferences?.modelType }
                ?: MODEL_TYPES.first()
        ),
        modelConfigs = ModelConfigs(
            modelTypes = MODEL_TYPES,
            maxSelection = 1
        ),
    )
}
