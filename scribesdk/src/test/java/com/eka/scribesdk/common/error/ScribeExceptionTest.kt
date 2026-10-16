package com.eka.scribesdk.common.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScribeExceptionTest {

    @Test
    fun `constructor sets code and message`() {
        val ex = ScribeException(ErrorCode.UPLOAD_FAILED, "upload exploded")
        assertEquals(ErrorCode.UPLOAD_FAILED, ex.code)
        assertEquals("upload exploded", ex.message)
        assertNull(ex.cause)
    }

    @Test
    fun `constructor with cause chains correctly`() {
        val cause = RuntimeException("root")
        val ex = ScribeException(ErrorCode.NETWORK_UNAVAILABLE, "offline", cause)
        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, ex.code)
        assertEquals("offline", ex.message)
        assertEquals(cause, ex.cause)
    }

    @Test
    fun `ScribeException is an Exception`() {
        val ex = ScribeException(ErrorCode.UNKNOWN, "test")
        assertTrue(ex is Exception)
    }

    @Test
    fun `all ErrorCode values can construct ScribeException`() {
        for (code in ErrorCode.entries) {
            val ex = ScribeException(code, "msg-${code.name}")
            assertEquals(code, ex.code)
            assertEquals("msg-${code.name}", ex.message)
        }
    }

    @Test
    fun `ErrorCode contains all expected values`() {
        val expected = setOf(
            "MIC_PERMISSION_DENIED", "SESSION_ALREADY_ACTIVE", "INVALID_CONFIG",
            "ENCODER_FAILED", "UPLOAD_FAILED", "MODEL_LOAD_FAILED",
            "NETWORK_UNAVAILABLE", "DB_ERROR", "INVALID_STATE_TRANSITION",
            "INIT_TRANSACTION_FAILED", "STOP_TRANSACTION_FAILED",
            "COMMIT_TRANSACTION_FAILED", "POLL_TIMEOUT",
            "TRANSCRIPTION_FAILED", "RETRY_EXHAUSTED", "UNKNOWN"
        )
        val actual = ErrorCode.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }
}
