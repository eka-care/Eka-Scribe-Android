package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.SessionEventName
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SessionLifecycleEventsTest {

    @Test
    fun `sessions api sessions report create and end under their own names`() {
        assertEquals(
            SessionEventName.CREATE_SESSION_SUCCESS,
            SessionLifecycleEvents.createSession(sessionsApi = true, success = true)
        )
        assertEquals(
            SessionEventName.CREATE_SESSION_FAILED,
            SessionLifecycleEvents.createSession(sessionsApi = true, success = false)
        )
        assertEquals(
            SessionEventName.END_SESSION_SUCCESS,
            SessionLifecycleEvents.endSession(sessionsApi = true, success = true)
        )
        assertEquals(
            SessionEventName.END_SESSION_FAILED,
            SessionLifecycleEvents.endSession(sessionsApi = true, success = false)
        )
    }

    @Test
    fun `legacy sessions keep the transaction names`() {
        assertEquals(
            SessionEventName.INIT_TRANSACTION_SUCCESS,
            SessionLifecycleEvents.createSession(sessionsApi = false, success = true)
        )
        assertEquals(
            SessionEventName.INIT_TRANSACTION_FAILED,
            SessionLifecycleEvents.createSession(sessionsApi = false, success = false)
        )
        assertEquals(
            SessionEventName.STOP_TRANSACTION_SUCCESS,
            SessionLifecycleEvents.endSession(sessionsApi = false, success = true)
        )
        assertEquals(
            SessionEventName.STOP_TRANSACTION_FAILED,
            SessionLifecycleEvents.endSession(sessionsApi = false, success = false)
        )
    }

    @Test
    fun `event messages name the call that was made`() {
        assertEquals("Create session", SessionLifecycleEvents.createLabel(sessionsApi = true))
        assertEquals("Init transaction", SessionLifecycleEvents.createLabel(sessionsApi = false))
        assertEquals("End session", SessionLifecycleEvents.endLabel(sessionsApi = true))
        assertEquals("Stop transaction", SessionLifecycleEvents.endLabel(sessionsApi = false))
    }
}
