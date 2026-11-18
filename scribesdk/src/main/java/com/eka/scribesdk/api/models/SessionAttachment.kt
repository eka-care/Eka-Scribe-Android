package com.eka.scribesdk.api.models

/**
 * A medical-records document attached to a session's context.
 * @param documentId the server document_id (not a local id of a record that has not synced yet)
 * @param patientOid the patient the document belongs to
 */
data class SessionAttachment(
    val documentId: String,
    val patientOid: String? = null,
)
