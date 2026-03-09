package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.EventType
import com.eka.scribesdk.api.models.ScribeError
import com.eka.scribesdk.api.models.SessionEvent
import com.eka.scribesdk.api.models.SessionEventName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionEventEmitterTest {

    @Test
    fun `emit forwards event to callback with correct fields`() {
        val events = mutableListOf<SessionEvent>()
        val callback = object : EkaScribeCallback {
            override fun onSessionStarted(sessionId: String) {}
            override fun onSessionPaused(sessionId: String) {}
            override fun onSessionResumed(sessionId: String) {}
            override fun onSessionStopped(sessionId: String, chunkCount: Int) {}
            override fun onError(error: ScribeError) {}
            override fun onSessionEvent(event: SessionEvent) {
                events.add(event)
            }
        }

        val emitter = SessionEventEmitter(callback, "session-1")
        emitter.emit(
            SessionEventName.RECORDING_STARTED,
            EventType.SUCCESS,
            "Recording started",
            mapOf("key" to "value")
        )

        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("session-1", event.sessionId)
        assertEquals(SessionEventName.RECORDING_STARTED, event.eventName)
        assertEquals(EventType.SUCCESS, event.eventType)
        assertEquals("Recording started", event.message)
        assertEquals("value", event.metadata["key"])
    }

    @Test
    fun `emit with null callback does not crash`() {
        val emitter = SessionEventEmitter(null, "session-2")
        // Should not throw
        emitter.emit(SessionEventName.SESSION_PAUSED, EventType.INFO, "Paused")
    }

    @Test
    fun `emit with empty metadata defaults to empty map`() {
        val events = mutableListOf<SessionEvent>()
        val callback = object : EkaScribeCallback {
            override fun onSessionStarted(sessionId: String) {}
            override fun onSessionPaused(sessionId: String) {}
            override fun onSessionResumed(sessionId: String) {}
            override fun onSessionStopped(sessionId: String, chunkCount: Int) {}
            override fun onError(error: ScribeError) {}
            override fun onSessionEvent(event: SessionEvent) {
                events.add(event)
            }
        }

        val emitter = SessionEventEmitter(callback, "session-3")
        emitter.emit(SessionEventName.SESSION_COMPLETED, EventType.SUCCESS, "Done")

        assertTrue(events[0].metadata.isEmpty())
    }
}
