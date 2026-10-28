package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.SessionEventName

/**
 * Event names for the create and end steps of a session. Sessions API sessions report them as
 * Create Session / End Session; legacy sessions keep their transaction names.
 */
internal object SessionLifecycleEvents {

    fun createSession(sessionsApi: Boolean, success: Boolean): SessionEventName = when {
        sessionsApi && success -> SessionEventName.CREATE_SESSION_SUCCESS
        sessionsApi -> SessionEventName.CREATE_SESSION_FAILED
        success -> SessionEventName.INIT_TRANSACTION_SUCCESS
        else -> SessionEventName.INIT_TRANSACTION_FAILED
    }

    fun endSession(sessionsApi: Boolean, success: Boolean): SessionEventName = when {
        sessionsApi && success -> SessionEventName.END_SESSION_SUCCESS
        sessionsApi -> SessionEventName.END_SESSION_FAILED
        success -> SessionEventName.STOP_TRANSACTION_SUCCESS
        else -> SessionEventName.STOP_TRANSACTION_FAILED
    }

    /** Label used in event messages, e.g. "Create session failed: ..." or "Init transaction failed: ...". */
    fun createLabel(sessionsApi: Boolean): String = if (sessionsApi) "Create session" else "Init transaction"

    fun endLabel(sessionsApi: Boolean): String = if (sessionsApi) "End session" else "Stop transaction"
}
