package com.eka.scribesdk.data.remote.models.responses

import com.google.gson.JsonObject
import com.eka.scribesdk.api.models.SessionPatient
import com.eka.scribesdk.api.models.SessionDetails
import com.eka.scribesdk.api.models.SessionAttachment
import com.eka.scribesdk.api.models.LinkedSession

/**
 * document_id -> document_name from "List Session Documents"
 * (`{"status": "success", "data": {"documents": [{"document_id": ..., "document_name": ...}]}}`).
 * Parsed leniently: anything unexpected yields no names and callers fall back to template titles.
 */
internal fun JsonObject.toDocumentNames(): Map<String, String> {
    val data = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this
    val documents = data.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    return documents
        .mapNotNull { element ->
            val document = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val id = document.primitiveString("document_id") ?: return@mapNotNull null
            val name = document.primitiveString("document_name")?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            id to name
        }
        .toMap()
}

/** document_id -> `created_at` (epoch seconds) from "List Session Documents"; documents without one are left out. */
internal fun JsonObject.toDocumentCreatedAt(): Map<String, Long> {
    val data = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this
    val documents = data.get("documents")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyMap()
    return documents.mapNotNull { element ->
        val document = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = document.primitiveString("document_id") ?: return@mapNotNull null
        val createdAt = document.get("created_at")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return@mapNotNull null
        id to createdAt
    }.toMap()
}

/**
 * `data.audio_matrix.quality` (0..1) from "List Session Documents"; Get Session does not return it.
 * Null when absent or not a number.
 */
internal fun JsonObject.toAudioQuality(): Double? {
    val data = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this
    val quality = data.get("audio_matrix")?.takeIf { it.isJsonObject }?.asJsonObject?.get("quality")
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } ?: return null
    return quality.asDouble
}

/**
 * Patient and context (linked sessions, attachments) from "List Session Documents" / Get Session
 * Details. Parsed leniently: missing or unexpected parts are left empty.
 */
internal fun JsonObject.toSessionDetails(sessionId: String): SessionDetails {
    val data = get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: this
    val patient = data.get("patient_details")?.takeIf { it.isJsonObject }?.asJsonObject?.let { p ->
        val oid = p.primitiveString("oid")?.takeIf { it.isNotBlank() }
        val name = (p.primitiveString("name") ?: p.primitiveString("username"))?.takeIf { it.isNotBlank() }
        if (oid == null && name == null) null else SessionPatient(oid = oid, name = name)
    }
    val context = data.get("context")?.takeIf { it.isJsonObject }?.asJsonObject
    val linked = context?.get("past_sessions")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        when {
            // {"session_id": ..., "date_epoch": ...} or a bare id
            element.isJsonObject -> element.asJsonObject.let { o ->
                val id = o.primitiveString("session_id") ?: o.primitiveString("id") ?: return@mapNotNull null
                LinkedSession(id, o.get("date_epoch")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong)
            }

            element.isJsonPrimitive -> LinkedSession(element.asString, null)
            else -> null
        }
    }.orEmpty()
    val attachments = context?.get("attachments")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        val id = o.primitiveString("id") ?: return@mapNotNull null
        SessionAttachment(documentId = id, patientOid = o.primitiveString("patient_oid") ?: o.primitiveString("patient_id"))
    }.orEmpty()
    return SessionDetails(sessionId = sessionId, patient = patient, linkedSessions = linked, attachments = attachments)
}

private fun JsonObject.primitiveString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
