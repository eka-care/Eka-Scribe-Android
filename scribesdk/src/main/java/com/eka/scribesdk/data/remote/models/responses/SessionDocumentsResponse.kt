package com.eka.scribesdk.data.remote.models.responses

import com.google.gson.JsonObject

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

private fun JsonObject.primitiveString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
