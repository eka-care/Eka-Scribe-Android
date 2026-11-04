package com.eka.scribesdk.api.models

/**
 * What a session is linked to, from Get Session Details (`GET voice/api/v1/sessions/{id}`):
 * its patient and the context added with [com.eka.scribesdk.api.EkaScribe.addSessionContext].
 */
data class SessionDetails(
    val sessionId: String,
    /** Null when the session has no patient. */
    val patient: SessionPatient?,
    val linkedSessions: List<LinkedSession>,
    val attachments: List<SessionAttachment>,
)

data class SessionPatient(
    val oid: String?,
    val name: String?,
)

data class LinkedSession(
    val sessionId: String,
    /** When the linked session happened (epoch seconds), if the server sent it. */
    val dateEpochSeconds: Long?,
)
