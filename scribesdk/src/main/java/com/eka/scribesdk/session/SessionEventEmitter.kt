package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeCallback
import com.eka.scribesdk.api.models.EventType
import com.eka.scribesdk.api.models.SessionEvent
import com.eka.scribesdk.api.models.SessionEventName

internal class SessionEventEmitter(
    private val callback: EkaScribeCallback?,
    private val sessionId: String
) {
    fun emit(
        eventName: SessionEventName,
        eventType: EventType,
        message: String,
        metadata: Map<String, String> = emptyMap()
    ) {
        callback?.onSessionEvent(
            SessionEvent(
                sessionId = sessionId,
                eventName = eventName,
                eventType = eventType,
                message = message,
                metadata = metadata
            )
        )
    }
}
