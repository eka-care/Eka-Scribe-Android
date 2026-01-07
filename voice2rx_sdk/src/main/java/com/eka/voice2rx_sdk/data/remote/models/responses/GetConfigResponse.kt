package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.eka.voice2rx_sdk.common.Voice2RxInternalUtils.getModelTypes
import com.eka.voice2rx_sdk.sdkinit.models.ConsultationMode
import com.eka.voice2rx_sdk.sdkinit.models.ConsultationModeConfig
import com.eka.voice2rx_sdk.sdkinit.models.ModelConfigs
import com.eka.voice2rx_sdk.sdkinit.models.OutputTemplate
import com.eka.voice2rx_sdk.sdkinit.models.OutputTemplatesConfig
import com.eka.voice2rx_sdk.sdkinit.models.SelectedUserPreferences
import com.eka.voice2rx_sdk.sdkinit.models.SupportedLanguage
import com.eka.voice2rx_sdk.sdkinit.models.SupportedLanguagesConfig
import com.eka.voice2rx_sdk.sdkinit.models.UserConfigs
import com.google.gson.annotations.SerializedName

@Keep
data class GetConfigResponse(
    @SerializedName("data")
    var data: Data?
) {
    @Keep
    data class Data(
        @SerializedName("consultation_modes")
        var consultationModes: List<ConsultationMode?>?,
        @SerializedName("max_selection")
        var maxSelection: MaxSelection?,
        @SerializedName("my_templates")
        var myTemplates: List<MyTemplate?>?,
        @SerializedName("selected_preferences")
        var selectedPreferences: SelectedPreferences?,
        @SerializedName("settings")
        var settings: Settings?,
        @SerializedName("supported_languages")
        var supportedLanguages: List<SupportedLanguage?>?,
        @SerializedName("supported_output_formats")
        var supportedOutputFormats: List<SupportedOutputFormat?>?,
    ) {
        @Keep
        data class ConsultationMode(
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
            var languages: List<SupportedLanguage?>?,
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
        data class SupportedLanguage(
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

internal fun GetConfigResponse.Data.ConsultationMode.toConsultationMode(): ConsultationMode? {
    return id?.let { ConsultationMode(id = it, name = name ?: "", desc = desc ?: "") }
}

internal fun GetConfigResponse.Data.MyTemplate.toOutputFormat(): OutputTemplate? {
    return id?.let { OutputTemplate(id = it, name = name ?: "") }
}

internal fun GetConfigResponse.Data.SupportedLanguage.toSupportedLanguage(): SupportedLanguage? {
    return id?.let { SupportedLanguage(id = it, name = name ?: "") }
}


internal fun GetConfigResponse.Data.toUserConfigs(): UserConfigs {
    val supportedLanguages = mutableListOf<SupportedLanguage>()
    val supportedModes = mutableListOf<ConsultationMode>()
    val supportedOutputFormats = mutableListOf<OutputTemplate>()

    this.consultationModes?.mapNotNull { it?.toConsultationMode() }
        ?.let { supportedModes.addAll(it) }
    this.myTemplates?.mapNotNull { it?.toOutputFormat() }?.let { supportedOutputFormats.addAll(it) }
    this.supportedLanguages?.mapNotNull { it?.toSupportedLanguage() }
        ?.let { supportedLanguages.addAll(it) }
    return UserConfigs(
        consultationModes = ConsultationModeConfig(
            modes = supportedModes,
            maxSelection = this.maxSelection?.consultationModes ?: 1
        ),
        supportedLanguages = SupportedLanguagesConfig(
            languages = supportedLanguages,
            maxSelection = this.maxSelection?.supportedLanguages ?: 1
        ),
        outputTemplates = OutputTemplatesConfig(
            templates = supportedOutputFormats,
            maxSelection = this.maxSelection?.supportedOutputFormats ?: 1
        ),
        selectedUserPreferences = SelectedUserPreferences(
            consultationMode = supportedModes.firstOrNull { it.id == this.selectedPreferences?.consultationModeId },
            languages = this.selectedPreferences?.languages?.mapNotNull { it?.toSupportedLanguage() }
                ?: emptyList(),
            outputTemplates = this.selectedPreferences?.outputFormats?.mapNotNull { it?.toOutputFormat() }
                ?: emptyList(),
            modelType = getModelTypes().firstOrNull { it.id == this.selectedPreferences?.modelType }
                ?: getModelTypes().first()
        ),
        modelConfigs = ModelConfigs(
            modelTypes = getModelTypes(),
            maxSelection = 1
        )
    )
}