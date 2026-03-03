package com.eka.scribesdk.data.local.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

internal class UploadStateTest {

    @Test
    fun `all 4 states exist`() {
        assertEquals(4, UploadState.entries.size)
    }

    @Test
    fun `valueOf works for all states`() {
        val expected = listOf("PENDING", "IN_PROGRESS", "SUCCESS", "FAILED")
        for (name in expected) {
            assertEquals(name, UploadState.valueOf(name).name)
        }
    }

    @Test
    fun `ordinal values are sequential`() {
        assertEquals(0, UploadState.PENDING.ordinal)
        assertEquals(1, UploadState.IN_PROGRESS.ordinal)
        assertEquals(2, UploadState.SUCCESS.ordinal)
        assertEquals(3, UploadState.FAILED.ordinal)
    }
}
