package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

@Keep
internal data class CreateSessionResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("status")
    val status: String?,
    /** HTTP upload endpoint (string) or a storage payload (object), depending on [storageProvider]. */
    @SerializedName("upload_url")
    val uploadUrl: JsonElement?,
    @SerializedName("storage_provider")
    val storageProvider: String?,
    @SerializedName("created_at")
    val createdAt: String?,
    @SerializedName("expires_at")
    val expiresAt: String?
)

@Keep
internal data class PatchSessionResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("message")
    val message: String?
)

@Keep
internal data class UploadAudioResponse(
    @SerializedName("success")
    val success: Boolean?
)

@Keep
internal data class EndSessionResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("message")
    val message: String?,
    @SerializedName("audio_files_received")
    val audioFilesReceived: Int?,
    @SerializedName("audio_files")
    val audioFiles: List<String>?
)

/** Error body for the Sessions API: `{ "error": { "code", "message", "details" } }`. */
@Keep
internal data class SessionsErrorResponse(
    @SerializedName("error")
    val error: SessionsApiError?,
    @SerializedName("message")
    val message: String?
)

@Keep
internal data class SessionsApiError(
    @SerializedName("code")
    val code: String?,
    @SerializedName("message")
    val message: String?
)

internal fun SessionsErrorResponse?.readableMessage(fallback: String): String =
    this?.error?.message ?: this?.message ?: this?.error?.code ?: fallback

/** 200 body of `GET /voice/v1/sessions/{session_id}/audio`: one presigned URL for the combined MP3. */
@Keep
internal data class SessionAudioResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("audio_url")
    val audioUrl: String?,
    @SerializedName("expires_in")
    val expiresIn: Long?,
    @SerializedName("expires_at")
    val expiresAt: Long?
)

/** 202 body of `POST /voice/v1/sessions/{session_id}/process/template/{template_id}`. */
@Keep
internal data class ProcessTemplateResponse(
    @SerializedName("session_id")
    val sessionId: String?,
    @SerializedName("template_id")
    val templateId: String?,
    /** The document being generated; poll Get Session with `?document_id=` until it is done. */
    @SerializedName("document_id")
    val documentId: String?,
    @SerializedName("status")
    val status: String?,
    @SerializedName("message")
    val message: String?
)

/** `POST /voice/api/v1/documents` response: `{ "status", "data": Document }`. */
@Keep
internal data class DocumentEnvelopeResponse(
    @SerializedName("status")
    val status: String?,
    @SerializedName("data")
    val data: DocumentData?
) {
    @Keep
    data class DocumentData(
        @SerializedName("document_id")
        val documentId: String?,
        @SerializedName("session_id")
        val sessionId: String?,
        @SerializedName("status")
        val status: String?,
        /** Content URL, valid for a short time: PUT after create/update, GET from Get Document. */
        @SerializedName("presigned_url")
        val presignedUrl: String?,
        /** `custom`, `context`, `transcript`, `integration`. */
        @SerializedName("document_type")
        val documentType: String? = null,
        /** Publish state, e.g. `{"emr_webhook": {"status": "success", ...}}` once pushed to the EMR. */
        @SerializedName("publish")
        val publish: JsonObject? = null
    )
}
