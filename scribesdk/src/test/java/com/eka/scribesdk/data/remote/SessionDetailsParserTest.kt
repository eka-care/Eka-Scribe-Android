package com.eka.scribesdk.data.remote

import com.eka.scribesdk.data.remote.models.responses.toSessionDetails
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailsParserTest {

    @Test
    fun `reads patient, linked sessions and attachments`() {
        val json = JsonParser.parseString(
            """{"status":"success","data":{"patient_details":{"name":"Rahul Rao","oid":"p-1"},
               "context":{"past_sessions":[{"session_id":"s-0","date_epoch":1762165140}],
               "attachments":[{"id":"doc-1","patient_oid":"p-1"}],"documents":["ctx-1"]}}}"""
        ).asJsonObject

        val details = json.toSessionDetails("s-1")

        assertEquals("p-1", details.patient?.oid)
        assertEquals("Rahul Rao", details.patient?.name)
        assertEquals("s-0", details.linkedSessions.single().sessionId)
        assertEquals(1762165140L, details.linkedSessions.single().dateEpochSeconds)
        assertEquals("doc-1", details.attachments.single().documentId)
        assertEquals("p-1", details.attachments.single().patientOid)
    }

    @Test
    fun `empty patient and no context give empty details`() {
        val json = JsonParser.parseString("""{"data":{"patient_details":{}}}""").asJsonObject

        val details = json.toSessionDetails("s-1")

        assertNull(details.patient)
        assertTrue(details.linkedSessions.isEmpty())
        assertTrue(details.attachments.isEmpty())
    }
}
