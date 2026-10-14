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

    @Test
    fun `sessionsApiSessionId fits the 16-32 char limit`() {
        repeat(50) {
            val id = IdGenerator.sessionsApiSessionId()
            assertTrue("Should start with 'a-'", id.startsWith("a-"))
            assertEquals(32, id.length)
            assertTrue("No dashes after the prefix", !id.drop(2).contains("-"))
        }
    }

    @Test
    fun `sessionId with flag picks the matching format`() {
        assertEquals(32, IdGenerator.sessionId(forSessionsApi = true).length)
        assertEquals(38, IdGenerator.sessionId(forSessionsApi = false).length)
    }
}
