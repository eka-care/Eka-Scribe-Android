package com.eka.scribesdk.data.remote.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadResultTest {

    @Test
    fun `Success contains url`() {
        val result = UploadResult.Success("s3://bucket/file.m4a")
        assertTrue(result is UploadResult)
        assertEquals("s3://bucket/file.m4a", result.url)
    }

    @Test
    fun `Failure contains error and isRetryable true`() {
        val result = UploadResult.Failure("Network timeout", isRetryable = true)
        assertTrue(result is UploadResult)
        assertEquals("Network timeout", result.error)
        assertTrue(result.isRetryable)
    }

    @Test
    fun `Failure with isRetryable false`() {
        val result = UploadResult.Failure("Permission denied", isRetryable = false)
        assertEquals("Permission denied", result.error)
        assertFalse(result.isRetryable)
    }

    @Test
    fun `sealed class exhaustive when check`() {
        val results = listOf(
            UploadResult.Success("url"),
            UploadResult.Failure("err", true)
        )
        for (result in results) {
            when (result) {
                is UploadResult.Success -> assertTrue(result.url.isNotEmpty())
                is UploadResult.Failure -> assertTrue(result.error.isNotEmpty())
            }
        }
    }

    @Test
    fun `data class equality`() {
        val a = UploadResult.Success("url")
        val b = UploadResult.Success("url")
        assertEquals(a, b)

        val c = UploadResult.Failure("err", true)
        val d = UploadResult.Failure("err", true)
        assertEquals(c, d)
    }
}
