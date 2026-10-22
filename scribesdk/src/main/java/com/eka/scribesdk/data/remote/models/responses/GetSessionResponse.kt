package com.eka.scribesdk.data.remote.models.responses

import android.util.Base64
import androidx.annotation.Keep
import com.eka.scribesdk.api.models.SectionData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Body of `GET /voice/v1/sessions/{session_id}` for 200 (completed), 202 (processing)
 * and 206 (partial). `templates` is kept raw because its items are free-form:
 * a list of single-key objects keyed by template_id.
 */
@Keep
internal data class GetSessionResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("language_detected")
    val languageDetected: String?,
    @SerializedName("transcript")
    val transcript: String?,
    @SerializedName("templates")
    val templates: JsonElement?,
    @SerializedName("message")
    val message: String?
)

/** Base64 seam so the adapter can run in JVM unit tests (android.util.Base64 is a stub there). */
internal interface Base64Codec {
    fun encode(text: String): String
    fun decode(base64: String): ByteArray?
}

internal object AndroidBase64Codec : Base64Codec {
    override fun encode(text: String): String =
        Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    override fun decode(base64: String): ByteArray? =
        try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            null
        }
}

private const val TRANSCRIPT = "transcript"
internal const val CONTEXT = "context"
internal const val CONTEXT_NAME = "Context"
private const val CONTEXT_TEMPLATE_ID = "__non_tmp_doc"

// Mobile sessions get this system document; it is always shown as the one "Clinical Notes"
private const val CLINICAL_NOTES_TEMPLATE = "clinical_notes_template"
private const val CLINICAL_NOTES_NAME = "Clinical Notes"

/** Id of the empty Context note shown when a session has no context yet; saving it creates one. */
internal fun contextPlaceholderId(sessionId: String): String = "${sessionId}_$CONTEXT"
private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/\\r\\n]+={0,2}$")
private val gson = Gson()

/**
 * Converts the Sessions API result into the legacy [ScribeResultResponse] shape, so the existing
 * mappers (toSessionResult / toTranscriptResult / toOutputTemplatesResult) work unchanged.
 * Values are re-encoded as base64 because those mappers decode base64.
 */
internal fun GetSessionResponse.toScribeResultResponse(
    codec: Base64Codec = AndroidBase64Codec,
    /** templateId -> display name (session config, then template catalog); used when a note has no name. */
    templateNames: Map<String, String> = emptyMap(),
    /** document_id -> the note's own current name, from "List Session Documents"; wins over [templateNames]. */
    documentNames: Map<String, String> = emptyMap(),
    /**
     * document_id -> text of the session's context document. Get Session lists that document
     * without its text, so it is downloaded separately.
     */
    contextTexts: Map<String, String> = emptyMap(),
    /**
     * Always show a Context note: editable with its text; read-only and empty when its text
     * could not be read; empty and editable ([contextPlaceholderId]) when the session has none.
     * When false, a context is shown only if its text is in [contextTexts].
     */
    alwaysShowContext: Boolean = false,
    /** Overall audio quality (0..1); Get Session lacks it, so it comes from "List Session Documents". */
    audioQuality: Double? = null,
    /** document_id -> created_at (epoch seconds), from "List Session Documents"; picks the newest Clinical Notes. */
    documentCreatedAt: Map<String, Long> = emptyMap()
): ScribeResultResponse {
    val custom = mutableListOf<ScribeResultResponse.Data.Output?>()
    val contexts = mutableListOf<ScribeResultResponse.Data.Output?>()
    val integration = mutableListOf<ScribeResultResponse.Data.Integration?>()
    var transcriptDocumentId: String? = null
    var hasContextDocument = false

    for ((templateId, entry) in templateEntries()) {
        val documentType = entry.stringOrNull("document_type")?.lowercase()
        val documentId = entry.stringOrNull("document_id")
        val status = entry.stringOrNull("status").toResultStatus()
        val name = entry.stringOrNull("name")
            ?: entry.stringOrNull("document_name")
            ?: entry.stringOrNull("template_name")
            ?: documentId?.let { documentNames[it] }
            ?: templateNames[templateId]?.takeIf { it.isNotBlank() }
            ?: templateId

        when {
            documentType == TRANSCRIPT || templateId == TRANSCRIPT -> transcriptDocumentId = documentId
            documentType == CONTEXT -> {
                if (documentId == null) continue
                hasContextDocument = true
                val contextName = documentNames[documentId] ?: CONTEXT_NAME
                val text = contextTexts[documentId]
                if (text != null) {
                    contexts += contextNote(documentId, templateId, contextName, text, codec)
                } else if (alwaysShowContext) {
                    // Text could not be read: shown, but read-only so an edit can't overwrite it
                    integration += ScribeResultResponse.Data.Integration(
                        documentId = documentId,
                        documentType = CONTEXT,
                        errors = null,
                        name = contextName,
                        status = ResultStatus.SUCCESS,
                        templateId = templateId,
                        type = OutputType.TEXT,
                        value = codec.encode(""),
                        warnings = null
                    )
                }
            }
            documentType != null && (documentType.contains("integration") || documentType.contains("eka_emr")) -> {
                // System documents often come back empty (null, {}, {"prescription": {}}); those are
                // not notes, except Clinical Notes, which is always shown (blank when empty)
                val text = entry.get("data")?.takeIf { it.hasContent() }?.toPlainText(codec)
                val isClinicalNotes = templateId == CLINICAL_NOTES_TEMPLATE
                integration += ScribeResultResponse.Data.Integration(
                    documentId = documentId,
                    documentType = documentType,
                    errors = null,
                    name = if (isClinicalNotes && name == templateId) CLINICAL_NOTES_NAME else name,
                    status = if (isClinicalNotes) ResultStatus.SUCCESS else status,
                    templateId = templateId,
                    type = OutputType.EKA_EMR,
                    value = (if (isClinicalNotes) text.orEmpty() else text)?.let(codec::encode),
                    warnings = null
                )
            }

            else -> {
                val (type, text) = entry.get("data").toTypedContent(entry.stringOrNull("type"), codec)
                custom += ScribeResultResponse.Data.Output(
                    documentId = documentId,
                    // toOutputTemplatesResult keeps only "custom"/"document"
                    documentType = "custom",
                    errors = null,
                    name = name,
                    status = status,
                    templateId = templateId,
                    type = type,
                    value = text?.let(codec::encode),
                    warnings = null
                )
            }
        }
    }

    keepNewestClinicalNotes(custom, integration, documentCreatedAt)

    if (alwaysShowContext && !hasContextDocument && !sessionId.isNullOrBlank()) {
        contexts += contextNote(contextPlaceholderId(sessionId), CONTEXT_TEMPLATE_ID, CONTEXT_NAME, "", codec)
    }

    val transcripts = transcript?.takeIf { it.isNotBlank() }?.let { text ->
        listOf(
            ScribeResultResponse.Data.Transcript(
                documentId = transcriptDocumentId ?: "${sessionId.orEmpty()}_$TRANSCRIPT",
                documentType = TRANSCRIPT,
                errors = null,
                lang = languageDetected,
                status = ResultStatus.SUCCESS,
                type = OutputType.TEXT,
                value = codec.encode(text),
                warnings = null
            )
        )
    }

    return ScribeResultResponse(
        data = ScribeResultResponse.Data(
            audioMatrix = audioQuality?.let { ScribeResultResponse.Data.AudioMatrix(quality = it) },
            createdAt = createdAt,
            output = null,
            templateResults = ScribeResultResponse.Data.TemplateResults(
                // Context after the notes, so a note (not the context) is what opens first
                custom = custom + contexts,
                integration = integration,
                transcript = transcripts
            )
        )
    )
}

private fun isClinicalNotes(name: String?, templateId: String?): Boolean =
    templateId == CLINICAL_NOTES_TEMPLATE || name?.trim().equals(CLINICAL_NOTES_NAME, ignoreCase = true)

/**
 * One "Clinical Notes": the system document, notes generated with "+" and every regeneration from
 * the Clinical Notes chip are separate documents, so only the newest one that can be shown is kept
 * (without timestamps, a generated note wins over the system document). A failed or unfinished
 * regeneration therefore never hides the note that is already there.
 */
private fun keepNewestClinicalNotes(
    custom: MutableList<ScribeResultResponse.Data.Output?>,
    integration: MutableList<ScribeResultResponse.Data.Integration?>,
    createdAt: Map<String, Long>
) {
    val shownStatuses = setOf(ResultStatus.SUCCESS, ResultStatus.PARTIAL_COMPLETED)
    val generated = custom.filterNotNull()
        .filter { isClinicalNotes(it.name, it.templateId) && it.value != null && it.status in shownStatuses }
        .map { Triple(it.documentId, createdAt[it.documentId] ?: -1L, 1) }
    val system = integration.filterNotNull()
        .filter { isClinicalNotes(it.name, it.templateId) && it.value != null && it.status in shownStatuses }
        .map { Triple(it.documentId, createdAt[it.documentId] ?: -1L, 0) }
    val candidates = generated + system
    if (candidates.size < 2) return
    val keep = candidates.maxWith(compareBy({ it.second }, { it.third })).first
    custom.removeAll { it != null && isClinicalNotes(it.name, it.templateId) && it.documentId != keep }
    integration.removeAll { it != null && isClinicalNotes(it.name, it.templateId) && it.documentId != keep }
}

private fun contextNote(
    documentId: String,
    templateId: String,
    name: String,
    text: String,
    codec: Base64Codec
) = ScribeResultResponse.Data.Output(
    documentId = documentId,
    documentType = "custom",
    errors = null,
    name = name,
    status = ResultStatus.SUCCESS,
    templateId = templateId,
    type = OutputType.MARKDOWN,
    value = codec.encode(text),
    warnings = null
)

/** One note in a Get Session result. */
internal data class DocumentRef(
    val templateId: String,
    val documentId: String?,
    val status: String?,
    val documentType: String? = null
)

/** Every note in this result, in response order. */
internal fun GetSessionResponse.documentRefs(): List<DocumentRef> =
    templateEntries().map { (templateId, entry) ->
        DocumentRef(
            templateId,
            entry.stringOrNull("document_id"),
            entry.stringOrNull("status"),
            entry.stringOrNull("document_type")?.lowercase()
        )
    }

/** Flattens `templates` (list of `{template_id: {...}}`, or a plain object) into (templateId, entry) pairs. */
private fun GetSessionResponse.templateEntries(): List<Pair<String, JsonObject>> {
    val element = templates ?: return emptyList()
    val result = mutableListOf<Pair<String, JsonObject>>()

    fun addKeyed(obj: JsonObject) {
        // A flat entry carries its own template_id; otherwise each key is a template_id
        if (obj.has("document_id") || obj.has("status")) {
            obj.stringOrNull("template_id")?.let { result += it to obj }
            return
        }
        for ((key, value) in obj.entrySet()) {
            if (value.isJsonObject) result += key to value.asJsonObject
        }
    }

    when {
        element.isJsonArray -> element.asJsonArray.forEach { if (it.isJsonObject) addKeyed(it.asJsonObject) }
        element.isJsonObject -> addKeyed(element.asJsonObject)
    }
    return result
}

private fun String?.toResultStatus(): ResultStatus = when (this?.lowercase()) {
    "success", "completed" -> ResultStatus.SUCCESS
    "partial", "partial_success" -> ResultStatus.PARTIAL_COMPLETED
    "failure", "failed", "error" -> ResultStatus.FAILURE
    else -> ResultStatus.IN_PROGRESS
}

private fun JsonObject.stringOrNull(key: String): String? {
    val value = get(key) ?: return null
    return if (value.isJsonPrimitive) value.asString else null
}

/**
 * JSON object -> sections (one per key), JSON array of {title, value} -> sections,
 * string -> markdown (or JSON sections if it parses as such).
 */
private fun JsonElement?.toTypedContent(declaredType: String?, codec: Base64Codec): Pair<OutputType, String?> {
    if (this == null || isJsonNull) return OutputType.MARKDOWN to null
    if (isJsonObject) return OutputType.JSON to gson.toJson(asJsonObject.toSections())
    if (isJsonArray) return OutputType.JSON to gson.toJson(asJsonArray.toSections())

    val text = toPlainText(codec)
    val looksLikeSections = declaredType.equals("json", ignoreCase = true) || text.trimStart().startsWith("[")
    if (looksLikeSections && text.parsesAsSections()) return OutputType.JSON to text
    return OutputType.MARKDOWN to text
}

private fun JsonObject.toSections(): List<SectionData> =
    entrySet().map { (key, value) -> SectionData(title = key, value = value.displayText()) }

private fun JsonArray.toSections(): List<SectionData> =
    mapNotNull { item ->
        if (!item.isJsonObject) return@mapNotNull SectionData(title = null, value = item.displayText())
        val obj = item.asJsonObject
        SectionData(title = obj.stringOrNull("title"), value = obj.get("value")?.displayText())
    }

/** False for null, blank text, and objects/arrays that hold nothing but such values. */
private fun JsonElement.hasContent(): Boolean = when {
    isJsonNull -> false
    isJsonPrimitive -> !asJsonPrimitive.isString || asString.isNotBlank()
    isJsonObject -> asJsonObject.entrySet().any { (_, value) -> value.hasContent() }
    isJsonArray -> asJsonArray.any { it.hasContent() }
    else -> false
}

private fun JsonElement.displayText(): String =
    if (isJsonPrimitive) asString else if (isJsonNull) "" else toString()

private fun String.parsesAsSections(): Boolean =
    try {
        gson.fromJson(this, Array<SectionData>::class.java) != null
    } catch (e: Exception) {
        false
    }

/** A stored document's text (as downloaded from its presigned URL): base64 UTF-8 is decoded, plain text kept. */
internal fun decodeStoredText(raw: String, codec: Base64Codec = AndroidBase64Codec): String =
    JsonPrimitive(raw).toPlainText(codec)

/** String content as plain text; decodes it first if it is base64-encoded UTF-8. */
private fun JsonElement.toPlainText(codec: Base64Codec): String {
    if (!isJsonPrimitive) return toString()
    val raw = asString
    val compact = raw.replace("\r", "").replace("\n", "")
    // Markdown/plain text has spaces or punctuation that base64 never does
    if (compact.length < 8 || compact.length % 4 != 0 || !BASE64_PATTERN.matches(compact)) return raw
    val bytes = codec.decode(compact) ?: return raw
    return bytes.decodeUtf8OrNull() ?: raw
}

private fun ByteArray.decodeUtf8OrNull(): String? =
    try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(this))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }
