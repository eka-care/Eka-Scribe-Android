package com.eka.scribesdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/** Body for `POST /voice/v1/sessions`. Null fields are omitted. */
@Keep
internal data class CreateSessionRequest(
    @SerializedName("upload_type")
    val uploadType: String,
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("templates")
    val templates: List<String>? = null,
    @SerializedName("model")
    val model: String? = null,
    @SerializedName("language_hint")
    val languageHint: List<String>? = null,
    @SerializedName("patient_details")
    val patientDetails: Map<String, String>? = null,
    @SerializedName("additional_data")
    val additionalData: Map<String, String>? = null
)

/** Body for `PATCH /voice/v1/sessions/{session_id}`. Only non-null fields are sent. */
@Keep
internal data class PatchSessionRequest(
    @SerializedName("session_mode")
    val sessionMode: String? = null,
    @SerializedName("user_status")
    val userStatus: String? = null,
    @SerializedName("processing_status")
    val processingStatus: String? = null,
    /** Merged into the session's additional_data by the server. */
    @SerializedName("additional_data")
    val additionalData: Map<String, Any>? = null
)

/** Body for `POST /voice/v1/sessions/{session_id}/end`. */
@Keep
internal data class EndSessionRequest(
    @SerializedName("audio_files_sent")
    val audioFilesSent: Int
)

/** Body for `POST /voice/api/v1/documents`; with document_id it updates that document. */
@Keep
internal data class UpsertDocumentRequest(
    @SerializedName("session_id")
    val sessionId: String,
    @SerializedName("document_id")
    val documentId: String? = null,
    /** e.g. `{"emr_webhook": {"status": "unpublished", ...}}`; omitted when null. */
    @SerializedName("publish")
    val publish: JsonObject? = null
)
