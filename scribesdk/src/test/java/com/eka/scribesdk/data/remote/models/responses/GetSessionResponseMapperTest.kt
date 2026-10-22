package com.eka.scribesdk.data.remote.models.responses

import com.eka.scribesdk.api.models.SectionData
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class GetSessionResponseMapperTest {

    /** android.util.Base64 is a stub in JVM tests, so use the JDK codec. */
    private object JvmCodec : Base64Codec {
        override fun encode(text: String): String =
            java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        override fun decode(base64: String): ByteArray? =
            try {
                java.util.Base64.getMimeDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    private fun decode(value: String?): String =
        String(java.util.Base64.getDecoder().decode(value!!), Charsets.UTF_8)

    private fun response(templatesJson: String, transcript: String? = "Patient has fever") = GetSessionResponse(
        sessionId = "a-1",
        status = "completed",
        createdAt = "2026-10-14T13:08:02Z",
        languageDetected = "en",
        transcript = transcript,
        templates = JsonParser.parseString(templatesJson),
        message = null
    )

    @Test
    fun `object data becomes JSON sections, one per key`() {
        val result = response(
            """[{"soap": {"status": "success", "document_id": "doc_1", "document_type": "template",
                 "data": {"chief_complaint": "cough", "assessment": "bronchitis"}}}]"""
        ).toScribeResultResponse(JvmCodec)

        val output = result.data!!.templateResults!!.custom!!.single()!!
        assertEquals("soap", output.templateId)
        assertEquals("doc_1", output.documentId)
        assertEquals("custom", output.documentType)
        assertEquals(ResultStatus.SUCCESS, output.status)
        assertEquals(OutputType.JSON, output.type)
        val sections = Gson().fromJson(decode(output.value), Array<SectionData>::class.java).toList()
        assertEquals(listOf("chief_complaint", "assessment"), sections.map { it.title })
        assertEquals("cough", sections[0].value)
    }

    @Test
    fun `plain markdown string is kept as markdown`() {
        val result = response(
            """[{"notes": {"status": "success", "document_id": "doc_2", "data": "## Plan\nRest and fluids"}}]"""
        ).toScribeResultResponse(JvmCodec)

        val output = result.data!!.templateResults!!.custom!!.single()!!
        assertEquals(OutputType.MARKDOWN, output.type)
        assertEquals("## Plan\nRest and fluids", decode(output.value))
    }

    @Test
    fun `base64 string data is decoded before re-encoding`() {
        val markdown = "## Plan\nRest and fluids"
        val encoded = JvmCodec.encode(markdown)
        val result = response(
            """[{"notes": {"status": "success", "document_id": "doc_3", "data": "$encoded"}}]"""
        ).toScribeResultResponse(JvmCodec)

        assertEquals(markdown, decode(result.data!!.templateResults!!.custom!!.single()!!.value))
    }

    @Test
    fun `transcript string becomes the transcript entry and uses its document id`() {
        val result = response(
            """[{"transcript": {"status": "success", "document_id": "doc_t", "document_type": "transcript"}}]"""
        ).toScribeResultResponse(JvmCodec)

        val transcript = result.data!!.templateResults!!.transcript!!.single()!!
        assertEquals("doc_t", transcript.documentId)
        assertEquals(ResultStatus.SUCCESS, transcript.status)
        assertEquals("Patient has fever", decode(transcript.value))
        assertTrue(result.data!!.templateResults!!.custom!!.isEmpty())
    }

    @Test
    fun `failed template keeps failure status and no transcript means no transcript entry`() {
        val result = response(
            """[{"soap": {"status": "failure", "document_id": "doc_4"}}]""",
            transcript = null
        ).toScribeResultResponse(JvmCodec)

        val output = result.data!!.templateResults!!.custom!!.single()!!
        assertEquals(ResultStatus.FAILURE, output.status)
        assertNull(output.value)
        assertNull(result.data!!.templateResults!!.transcript)
    }

    @Test
    fun `eka emr documents go to integration`() {
        val result = response(
            """[{"emr": {"status": "success", "document_id": "doc_5", "document_type": "eka_emr_template",
                 "data": {"medications": "paracetamol"}}}]"""
        ).toScribeResultResponse(JvmCodec)

        val integration = result.data!!.templateResults!!.integration!!.single()!!
        assertEquals("emr", integration.templateId)
        assertEquals(OutputType.EKA_EMR, integration.type)
        assertTrue(decode(integration.value).contains("paracetamol"))
    }

    private val mobileSessionTemplates = """[
        {"eka_emr_template": {"status": "success", "document_id": "d1", "document_type": "integration",
            "data": {"prescription": {}}}},
        {"nic_template": {"status": "success", "document_id": "d2", "document_type": "integration", "data": {}}},
        {"clinical_notes_template": {"status": "success", "document_id": "d3",
            "document_type": "integration", "data": null}},
        {"9d9675c6": {"status": "success", "document_id": "d4", "document_type": "custom",
            "data": "No medical information is available in this conversation."}}
    ]"""

    @Test
    fun `empty system documents are hidden but Clinical Notes stays as a blank note`() {
        // Real mobile session: integration docs requested at create come back with no content
        val templates = response(mobileSessionTemplates).toScribeResultResponse(JvmCodec).toSessionResult("a-1").templates

        assertEquals(listOf("d4", "d3", "a-1_transcript"), templates.map { it.documentId })
        val clinicalNotes = templates[1]
        assertEquals("Clinical Notes", clinicalNotes.name)
        assertEquals("", clinicalNotes.sections.single().value)
    }

    @Test
    fun `a generated Clinical Notes note replaces the empty system one`() {
        val templates = response(mobileSessionTemplates)
            .toScribeResultResponse(JvmCodec, documentNames = mapOf("d4" to "Clinical Notes"))
            .toSessionResult("a-1").templates

        assertEquals(listOf("d4", "a-1_transcript"), templates.map { it.documentId })
    }

    @Test
    fun `only the newest Clinical Notes that can be shown is kept`() {
        val templates = response(
            """[
                {"clinical_notes_template": {"status": "success", "document_id": "sys", "document_type": "integration", "data": null}},
                {"9d96": {"status": "success", "document_id": "old", "document_type": "custom", "data": "First"}},
                {"9d96": {"status": "success", "document_id": "new", "document_type": "custom", "data": "Second"}},
                {"9d96": {"status": "failure", "document_id": "failed", "document_type": "custom", "data": null}},
                {"soap": {"status": "success", "document_id": "soap", "document_type": "custom", "data": "Other note"}}
            ]"""
        ).toScribeResultResponse(
            JvmCodec,
            documentNames = mapOf("old" to "Clinical Notes", "new" to "Clinical Notes", "failed" to "Clinical Notes"),
            documentCreatedAt = mapOf("sys" to 100L, "old" to 200L, "new" to 300L, "failed" to 400L)
        ).toSessionResult("a-1").templates

        assertEquals(listOf("new", "soap", "a-1_transcript"), templates.map { it.documentId })
    }

    @Test
    fun `template name comes from the saved config when the API has none`() {
        val result = response(
            """[{"t-1": {"status": "success", "document_id": "doc_7", "data": "Note"}}]"""
        ).toScribeResultResponse(JvmCodec, templateNames = mapOf("t-1" to "SOAP Note"))

        assertEquals("SOAP Note", result.data!!.templateResults!!.custom!!.single()!!.name)
    }

    @Test
    fun `a note's own name wins over the template name`() {
        val result = response(
            """[{"t-1": {"status": "success", "document_id": "doc_7", "data": "Note"}}]"""
        ).toScribeResultResponse(
            JvmCodec,
            templateNames = mapOf("t-1" to "SOAP Note"),
            documentNames = mapOf("doc_7" to "Follow-up visit")
        )

        assertEquals("Follow-up visit", result.data!!.templateResults!!.custom!!.single()!!.name)
    }

    @Test
    fun `templates given as a plain object are also read`() {
        val result = response(
            """{"soap": {"status": "success", "document_id": "doc_6", "data": "Note"}}"""
        ).toScribeResultResponse(JvmCodec)

        assertEquals("doc_6", result.data!!.templateResults!!.custom!!.single()!!.documentId)
    }
}
