package com.eka.scribesdk.session

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

internal class MarkdownToTiptapTest {

    private fun JsonObject.blocks() = this["content"].asJsonArray.map { it.asJsonObject }

    private fun JsonObject.texts(): List<String> =
        this["content"]?.asJsonArray?.map { it.asJsonObject["text"]?.asString ?: it.asJsonObject["type"].asString }
            ?: emptyList()

    @Test
    fun `blank lines split paragraphs and single line breaks are kept`() {
        val doc = MarkdownToTiptap.convert("Known diabetic\non metformin\n\nAllergic to penicillin")

        assertEquals("doc", doc["type"].asString)
        val blocks = doc.blocks()
        assertEquals(listOf("paragraph", "paragraph"), blocks.map { it["type"].asString })
        assertEquals(listOf("Known diabetic", "hardBreak", "on metformin"), blocks[0].texts())
        assertEquals(listOf("Allergic to penicillin"), blocks[1].texts())
    }

    @Test
    fun `headings and lists become their own nodes`() {
        val doc = MarkdownToTiptap.convert("## History\n- Fever\n- Cough\n1. CBC\n2) NS1")

        val blocks = doc.blocks()
        assertEquals(listOf("heading", "bulletList", "orderedList"), blocks.map { it["type"].asString })
        assertEquals(2, blocks[0]["attrs"].asJsonObject["level"].asInt)
        assertEquals(2, blocks[1]["content"].asJsonArray.size())
        val firstItem = blocks[2]["content"].asJsonArray[0].asJsonObject
        assertEquals("listItem", firstItem["type"].asString)
        assertEquals(listOf("CBC"), firstItem["content"].asJsonArray[0].asJsonObject.texts())
    }

    @Test
    fun `bold and italic become marks and escapes are removed`() {
        val paragraph = MarkdownToTiptap.convert("BP **high** and *mild* fever, dose 1\\.5").blocks().single()

        val nodes = paragraph["content"].asJsonArray.map { it.asJsonObject }
        assertEquals(listOf("BP ", "high", " and ", "mild", " fever, dose 1.5"), nodes.map { it["text"].asString })
        assertEquals("bold", nodes[1]["marks"].asJsonArray[0].asJsonObject["type"].asString)
        assertEquals("italic", nodes[3]["marks"].asJsonArray[0].asJsonObject["type"].asString)
        assertFalse(nodes[0].has("marks"))
    }

    @Test
    fun `empty text still gives a document the web accepts`() {
        val doc = MarkdownToTiptap.convert("  \n ")

        assertEquals(listOf("paragraph"), doc.blocks().map { it["type"].asString })
    }
}
