package com.eka.scribesdk.data.remote.models.requests

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/** Body of PATCH/DELETE voice/api/v1/sessions/{session_id}/context. Null lists are left out. */
@Keep
internal data class SessionContextRequest(
    @SerializedName("context") val context: Context,
) {
    @Keep
    internal data class Context(
        @SerializedName("past_sessions") val pastSessions: List<String>? = null,
        @SerializedName("attachments") val attachments: List<Attachment>? = null,
    )

    @Keep
    internal data class Attachment(
        @SerializedName("id") val id: String,
        @SerializedName("patient_oid") val patientOid: String? = null,
    )
}
