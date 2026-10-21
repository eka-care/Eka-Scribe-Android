package com.eka.scribesdk.data.remote.models.responses

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class SessionDocumentsResponseTest {

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    @Test
    fun `reads document names from the documented shape`() {
        val names = json(
            """{"status": "success", "data": {"session_id": "a-1", "documents": [
                 {"document_id": "d1", "template_id": "t-1", "document_name": "SOAP Note", "type": "custom"},
                 {"document_id": "d2", "template_id": "eka_emr_template", "document_name": "Eka EMR"}]}}"""
        ).toDocumentNames()

        assertEquals(mapOf("d1" to "SOAP Note", "d2" to "Eka EMR"), names)
    }

    @Test
    fun `reads the audio quality score`() {
        assertEquals(
            0.85,
            json("""{"status": "success", "data": {"audio_matrix": {"quality": 0.85}, "documents": []}}""")
                .toAudioQuality()!!,
            0.0
        )
        assertNull(json("""{"data": {"audio_matrix": {}}}""").toAudioQuality())
        assertNull(json("""{"data": {"audio_matrix": {"quality": "n/a"}}}""").toAudioQuality())
    }

    @Test
    fun `also reads documents at the top level`() {
        val names = json("""{"documents": [{"document_id": "d1", "document_name": "SOAP Note"}]}""").toDocumentNames()

        assertEquals(mapOf("d1" to "SOAP Note"), names)
    }

    @Test
    fun `skips documents without an id or a usable name`() {
        val names = json(
            """{"data": {"documents": [
                 {"document_id": "d1", "document_name": " "},
                 {"document_id": "d2"},
                 {"document_name": "No id"},
                 "not an object",
                 {"document_id": "d3", "document_name": "Kept"}]}}"""
        ).toDocumentNames()

        assertEquals(mapOf("d3" to "Kept"), names)
    }

    @Test
    fun `unexpected shapes give no names`() {
        assertEquals(emptyMap<String, String>(), json("""{"status": "success", "data": "oops"}""").toDocumentNames())
        assertEquals(emptyMap<String, String>(), json("""{"data": {"documents": {}}}""").toDocumentNames())
    }
}
