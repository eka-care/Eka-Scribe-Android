package com.eka.scribesdk.data.remote.models.responses

import android.util.Base64
import androidx.annotation.Keep
import com.eka.scribesdk.api.models.SectionData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
private val BASE64_PATTERN = Regex("^[A-Za-z0-9+/\\r\\n]+={0,2}$")
private val gson = Gson()

/**
 * Converts the Sessions API result into the legacy [ScribeResultResponse] shape, so the existing
 * mappers (toSessionResult / toTranscriptResult / toOutputTemplatesResult) work unchanged.
 * Values are re-encoded as base64 because those mappers decode base64.
 */
internal fun GetSessionResponse.toScribeResultResponse(
    codec: Base64Codec = AndroidBase64Codec,
    /** templateId -> display name; the API returns no names, so they come from the local session config. */
    templateNames: Map<String, String> = emptyMap()
): ScribeResultResponse {
    val custom = mutableListOf<ScribeResultResponse.Data.Output?>()
    val integration = mutableListOf<ScribeResultResponse.Data.Integration?>()
    var transcriptDocumentId: String? = null

    for ((templateId, entry) in templateEntries()) {
        val documentType = entry.stringOrNull("document_type")?.lowercase()
        val documentId = entry.stringOrNull("document_id")
        val status = entry.stringOrNull("status").toResultStatus()
        val name = entry.stringOrNull("name")
            ?: entry.stringOrNull("document_name")
            ?: entry.stringOrNull("template_name")
            ?: templateNames[templateId]?.takeIf { it.isNotBlank() }
            ?: templateId

        when {
            documentType == TRANSCRIPT || templateId == TRANSCRIPT -> transcriptDocumentId = documentId
            documentType == "context" -> Unit
            documentType != null && (documentType.contains("integration") || documentType.contains("eka_emr")) -> {
                val text = entry.get("data")?.toPlainText(codec)
                integration += ScribeResultResponse.Data.Integration(
                    documentId = documentId,
                    documentType = documentType,
                    errors = null,
                    name = name,
                    status = status,
                    templateId = templateId,
                    type = OutputType.EKA_EMR,
                    value = text?.let(codec::encode),
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
            audioMatrix = null,
            createdAt = createdAt,
            output = null,
            templateResults = ScribeResultResponse.Data.TemplateResults(
                custom = custom,
                integration = integration,
                transcript = transcripts
            )
        )
    )
}

/** Template ids in this result, in response order. */
internal fun GetSessionResponse.templateIds(): List<String> = templateEntries().map { it.first }

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

private fun JsonElement.displayText(): String =
    if (isJsonPrimitive) asString else if (isJsonNull) "" else toString()

private fun String.parsesAsSections(): Boolean =
    try {
        gson.fromJson(this, Array<SectionData>::class.java) != null
    } catch (e: Exception) {
        false
    }

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
