package com.eka.scribesdk.common.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdGeneratorTest {

    @Test
    fun `sessionId starts with a- prefix`() {
        val id = IdGenerator.sessionId()
        assertTrue("Session ID should start with 'a-'", id.startsWith("a-"))
    }

    @Test
    fun `sessionId generates unique IDs`() {
        val ids = (1..100).map { IdGenerator.sessionId() }.toSet()
        assertEquals("100 generated IDs should all be unique", 100, ids.size)
    }

    @Test
    fun `chunkId combines sessionId and index`() {
        val chunkId = IdGenerator.chunkId("a-session-123", 5)
        assertEquals("a-session-123_5", chunkId)
    }

    @Test
    fun `chunkId with index zero`() {
        assertEquals("ses_0", IdGenerator.chunkId("ses", 0))
    }
}
