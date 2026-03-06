package com.eka.scribesdk.data.remote.models.responses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TemplatesResponseMapperTest {

    @Test
    fun `toTemplateItem maps all fields`() {
        val item = TemplatesResponse.Item(
            default = true,
            desc = "A description",
            id = "tpl-1",
            isFavorite = true,
            sectionIds = listOf("s1", "s2"),
            title = "Template One"
        )

        val result = item.toTemplateItem()!!

        assertEquals("tpl-1", result.id)
        assertEquals("Template One", result.title)
        assertEquals("A description", result.desc)
        assertTrue(result.default)
        assertTrue(result.isFavorite)
        assertEquals(listOf("s1", "s2"), result.sectionIds)
    }

    @Test
    fun `toTemplateItem returns null when id is null`() {
        val item = TemplatesResponse.Item(
            default = true,
            desc = "desc",
            id = null,
            isFavorite = false,
            sectionIds = listOf("s1"),
            title = "Title"
        )

        assertNull(item.toTemplateItem())
    }

    @Test
    fun `toTemplateItem provides defaults for null fields`() {
        val item = TemplatesResponse.Item(
            default = null,
            desc = null,
            id = "tpl-2",
            isFavorite = null,
            sectionIds = null,
            title = null
        )

        val result = item.toTemplateItem()!!

        assertEquals("tpl-2", result.id)
        assertEquals("", result.title)
        assertEquals("", result.desc)
        assertFalse(result.default)
        assertFalse(result.isFavorite)
        assertTrue(result.sectionIds.isEmpty())
    }

    @Test
    fun `toTemplateItem filters null sectionIds`() {
        val item = TemplatesResponse.Item(
            default = false,
            desc = "d",
            id = "tpl-3",
            isFavorite = false,
            sectionIds = listOf("s1", null, "s2", null),
            title = "T"
        )

        val result = item.toTemplateItem()!!
        assertEquals(listOf("s1", "s2"), result.sectionIds)
    }
}
