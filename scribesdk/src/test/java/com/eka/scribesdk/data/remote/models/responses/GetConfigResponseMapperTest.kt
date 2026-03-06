package com.eka.scribesdk.data.remote.models.responses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GetConfigResponseMapperTest {

    // =====================================================================
    // ConsultationModeItem.toConsultationMode()
    // =====================================================================

    @Test
    fun `toConsultationMode maps all fields`() {
        val item = GetConfigResponse.Data.ConsultationModeItem(
            id = "opd", name = "OPD", desc = "Out Patient"
        )
        val result = item.toConsultationMode()!!
        assertEquals("opd", result.id)
        assertEquals("OPD", result.name)
        assertEquals("Out Patient", result.desc)
    }

    @Test
    fun `toConsultationMode returns null when id is null`() {
        val item = GetConfigResponse.Data.ConsultationModeItem(
            id = null, name = "OPD", desc = "desc"
        )
        assertNull(item.toConsultationMode())
    }

    @Test
    fun `toConsultationMode defaults name and desc`() {
        val item = GetConfigResponse.Data.ConsultationModeItem(
            id = "mode-1", name = null, desc = null
        )
        val result = item.toConsultationMode()!!
        assertEquals("", result.name)
        assertEquals("", result.desc)
    }

    // =====================================================================
    // MyTemplate.toOutputFormat()
    // =====================================================================

    @Test
    fun `toOutputFormat maps correctly`() {
        val item = GetConfigResponse.Data.MyTemplate(id = "fmt-1", name = "Format One")
        val result = item.toOutputFormat()!!
        assertEquals("fmt-1", result.id)
        assertEquals("Format One", result.name)
    }

    @Test
    fun `toOutputFormat returns null when id is null`() {
        val item = GetConfigResponse.Data.MyTemplate(id = null, name = "X")
        assertNull(item.toOutputFormat())
    }

    @Test
    fun `toOutputFormat defaults name`() {
        val item = GetConfigResponse.Data.MyTemplate(id = "f1", name = null)
        assertEquals("", item.toOutputFormat()!!.name)
    }

    // =====================================================================
    // SupportedLanguageItem.toSupportedLanguage()
    // =====================================================================

    @Test
    fun `toSupportedLanguage maps correctly`() {
        val item = GetConfigResponse.Data.SupportedLanguageItem(id = "en", name = "English")
        val result = item.toSupportedLanguage()!!
        assertEquals("en", result.id)
        assertEquals("English", result.name)
    }

    @Test
    fun `toSupportedLanguage returns null when id is null`() {
        val item = GetConfigResponse.Data.SupportedLanguageItem(id = null, name = "Hindi")
        assertNull(item.toSupportedLanguage())
    }

    // =====================================================================
    // Data.toUserConfigs()
    // =====================================================================

    @Test
    fun `toUserConfigs maps all collections`() {
        val data = buildData(
            modes = listOf(
                GetConfigResponse.Data.ConsultationModeItem(
                    id = "opd",
                    name = "OPD",
                    desc = "Out Patient"
                ),
                GetConfigResponse.Data.ConsultationModeItem(
                    id = "ipd",
                    name = "IPD",
                    desc = "In Patient"
                )
            ),
            templates = listOf(
                GetConfigResponse.Data.MyTemplate("t1", "Template 1"),
                GetConfigResponse.Data.MyTemplate("t2", "Template 2")
            ),
            languages = listOf(
                GetConfigResponse.Data.SupportedLanguageItem("en", "English"),
                GetConfigResponse.Data.SupportedLanguageItem("hi", "Hindi")
            ),
            maxModes = 2,
            maxLangs = 3,
            maxFormats = 1
        )

        val configs = data.toUserConfigs()

        assertEquals(2, configs.consultationModes.modes.size)
        assertEquals("opd", configs.consultationModes.modes[0].id)
        assertEquals(2, configs.consultationModes.maxSelection)

        assertEquals(2, configs.supportedLanguages.languages.size)
        assertEquals("en", configs.supportedLanguages.languages[0].id)
        assertEquals(3, configs.supportedLanguages.maxSelection)

        assertEquals(2, configs.outputTemplates.templates.size)
        assertEquals(1, configs.outputTemplates.maxSelection)
    }

    @Test
    fun `toUserConfigs filters null items in collections`() {
        val data = buildData(
            modes = listOf(
                GetConfigResponse.Data.ConsultationModeItem(
                    id = "opd",
                    name = "OPD",
                    desc = "desc"
                ),
                null,
                GetConfigResponse.Data.ConsultationModeItem(
                    id = null,
                    name = "Invalid",
                    desc = "desc"
                )
            ),
            templates = listOf(null),
            languages = listOf(
                GetConfigResponse.Data.SupportedLanguageItem(null, "No ID")
            )
        )

        val configs = data.toUserConfigs()

        // Only "opd" has non-null id
        assertEquals(1, configs.consultationModes.modes.size)
        assertEquals("opd", configs.consultationModes.modes[0].id)

        // All templates filtered
        assertTrue(configs.outputTemplates.templates.isEmpty())

        // Language has null id
        assertTrue(configs.supportedLanguages.languages.isEmpty())
    }

    @Test
    fun `toUserConfigs defaults maxSelection to 1`() {
        val data = buildData(maxModes = null, maxLangs = null, maxFormats = null)
        val configs = data.toUserConfigs()

        assertEquals(1, configs.consultationModes.maxSelection)
        assertEquals(1, configs.supportedLanguages.maxSelection)
        assertEquals(1, configs.outputTemplates.maxSelection)
    }

    @Test
    fun `toUserConfigs maps selected preferences`() {
        val data = buildData(
            modes = listOf(
                GetConfigResponse.Data.ConsultationModeItem(
                    id = "opd",
                    name = "OPD",
                    desc = "desc"
                ),
                GetConfigResponse.Data.ConsultationModeItem(id = "ipd", name = "IPD", desc = "desc")
            ),
            selectedConsultationModeId = "ipd",
            selectedLanguages = listOf(
                GetConfigResponse.Data.SupportedLanguageItem("en", "English")
            ),
            selectedOutputFormats = listOf(
                GetConfigResponse.Data.MyTemplate("t1", "Template 1")
            ),
            selectedModelType = "pro"
        )

        val prefs = data.toUserConfigs().selectedUserPreferences

        assertEquals("ipd", prefs.consultationMode?.id)
        assertEquals(1, prefs.languages.size)
        assertEquals("en", prefs.languages[0].id)
        assertEquals(1, prefs.outputTemplates.size)
        assertEquals("t1", prefs.outputTemplates[0].id)
        assertEquals("pro", prefs.modelType?.id)
    }

    @Test
    fun `toUserConfigs defaults model type to lite when unknown`() {
        val data = buildData(selectedModelType = "unknown")
        val prefs = data.toUserConfigs().selectedUserPreferences
        assertEquals("lite", prefs.modelType?.id)
    }

    @Test
    fun `toUserConfigs consultation mode is null when no match`() {
        val data = buildData(
            modes = listOf(
                GetConfigResponse.Data.ConsultationModeItem(id = "opd", name = "OPD", desc = "desc")
            ),
            selectedConsultationModeId = "nonexistent"
        )
        val prefs = data.toUserConfigs().selectedUserPreferences
        assertNull(prefs.consultationMode)
    }

    @Test
    fun `toUserConfigs includes model configs with Lite and Pro`() {
        val data = buildData()
        val modelConfigs = data.toUserConfigs().modelConfigs

        assertEquals(2, modelConfigs.modelTypes.size)
        assertEquals("lite", modelConfigs.modelTypes[0].id)
        assertEquals("pro", modelConfigs.modelTypes[1].id)
        assertEquals(1, modelConfigs.maxSelection)
    }

    @Test
    fun `toUserConfigs handles null collections`() {
        val data = GetConfigResponse.Data(
            consultationModes = null,
            maxSelection = null,
            myTemplates = null,
            selectedPreferences = null,
            settings = null,
            supportedLanguages = null,
            supportedOutputFormats = null
        )

        val configs = data.toUserConfigs()

        assertTrue(configs.consultationModes.modes.isEmpty())
        assertTrue(configs.supportedLanguages.languages.isEmpty())
        assertTrue(configs.outputTemplates.templates.isEmpty())
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun buildData(
        modes: List<GetConfigResponse.Data.ConsultationModeItem?>? = null,
        templates: List<GetConfigResponse.Data.MyTemplate?>? = null,
        languages: List<GetConfigResponse.Data.SupportedLanguageItem?>? = null,
        maxModes: Int? = 1,
        maxLangs: Int? = 1,
        maxFormats: Int? = 1,
        selectedConsultationModeId: String? = null,
        selectedLanguages: List<GetConfigResponse.Data.SupportedLanguageItem?>? = null,
        selectedOutputFormats: List<GetConfigResponse.Data.MyTemplate?>? = null,
        selectedModelType: String? = null
    ): GetConfigResponse.Data {
        return GetConfigResponse.Data(
            consultationModes = modes,
            maxSelection = GetConfigResponse.Data.MaxSelection(
                consultationModes = maxModes,
                supportedLanguages = maxLangs,
                supportedOutputFormats = maxFormats
            ),
            myTemplates = templates,
            selectedPreferences = GetConfigResponse.Data.SelectedPreferences(
                autoDownload = null,
                consultationModeId = selectedConsultationModeId,
                languages = selectedLanguages,
                modelType = selectedModelType,
                outputFormats = selectedOutputFormats
            ),
            settings = null,
            supportedLanguages = languages,
            supportedOutputFormats = null
        )
    }
}
