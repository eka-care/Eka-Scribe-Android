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

private fun JsonObject.primitiveString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString
