package com.eka.scribesdk.api.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionConfigTest {

    @Test
    fun `default SessionConfig has correct defaults`() {
        val config = SessionConfig()
        assertEquals(listOf("en-IN"), config.languages)
        assertEquals("dictation", config.mode)
        assertEquals("pro", config.modelType)
        assertNull(config.outputTemplates)
        assertNull(config.patientDetails)
        assertNull(config.section)
        assertNull(config.speciality)
    }

    @Test
    fun `custom SessionConfig stores all values`() {
        val templates = listOf(
            OutputTemplate(templateId = "t1", templateType = "soap", templateName = "SOAP Note")
        )
        val patient = PatientDetail(
            age = 35, biologicalSex = "M", name = "John",
            patientId = "p-001", visitId = "v-001"
        )
        val config = SessionConfig(
            languages = listOf("en-IN", "hi-IN"),
            mode = "conversation",
            modelType = "lite",
            outputTemplates = templates,
            patientDetails = patient,
            section = "cardiology",
            speciality = "internal"
        )
        assertEquals(listOf("en-IN", "hi-IN"), config.languages)
        assertEquals("conversation", config.mode)
        assertEquals("lite", config.modelType)
        assertEquals(1, config.outputTemplates!!.size)
        assertEquals("t1", config.outputTemplates!![0].templateId)
        assertEquals(35, config.patientDetails!!.age)
        assertEquals("cardiology", config.section)
        assertEquals("internal", config.speciality)
    }

    @Test
    fun `OutputTemplate has correct defaults`() {
        val template = OutputTemplate(templateId = "t1", templateName = "Name")
        assertEquals("custom", template.templateType)
    }

    @Test
    fun `PatientDetail all fields nullable`() {
        val patient = PatientDetail()
        assertNull(patient.age)
        assertNull(patient.biologicalSex)
        assertNull(patient.name)
        assertNull(patient.patientId)
        assertNull(patient.visitId)
    }

    @Test
    fun `data class equality`() {
        val a = SessionConfig()
        val b = SessionConfig()
        assertEquals(a, b)
    }
}
