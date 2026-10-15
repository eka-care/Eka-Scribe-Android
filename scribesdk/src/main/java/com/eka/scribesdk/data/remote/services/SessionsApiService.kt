package com.eka.scribesdk.data.remote.services

import com.eka.scribesdk.data.remote.models.responses.CreateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.DocumentEnvelopeResponse
import com.eka.scribesdk.data.remote.models.responses.EndSessionResponse
import com.eka.scribesdk.data.remote.models.responses.GetSessionResponse
import com.eka.scribesdk.data.remote.models.responses.PatchSessionResponse
import com.eka.scribesdk.data.remote.models.responses.ProcessTemplateResponse
import com.eka.scribesdk.data.remote.models.responses.SessionAudioResponse
import com.eka.scribesdk.data.remote.models.responses.SessionsErrorResponse
import com.eka.scribesdk.data.remote.models.responses.UploadAudioResponse
import com.google.gson.JsonObject
import com.haroldadmin.cnradapter.NetworkResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * EkaScribe Sessions API (`/voice/v1/sessions`).
 * Docs: https://developer.eka.care/ekascribe/api-reference/sessions/overview
 *
 * Bodies are sent as [JsonObject] so optional fields are omitted instead of sent as null.
 */
internal interface SessionsApiService {

    @POST("voice/v1/sessions")
    suspend fun createSession(
        @Body request: JsonObject
    ): NetworkResponse<CreateSessionResponse, SessionsErrorResponse>

    @PATCH("voice/v1/sessions/{session_id}")
    suspend fun patchSession(
        @Path("session_id") sessionId: String,
        @Body request: JsonObject
    ): NetworkResponse<PatchSessionResponse, SessionsErrorResponse>

    /** Raw audio bytes. Content-Type is left unset; the server infers it from the file extension. */
    @POST("voice/v1/sessions/{session_id}/audio/{file_sequence}")
    suspend fun uploadAudio(
        @Path("session_id") sessionId: String,
        @Path("file_sequence") fileSequence: String,
        @Body audio: RequestBody
    ): NetworkResponse<UploadAudioResponse, SessionsErrorResponse>

    @POST("voice/v1/sessions/{session_id}/end")
    suspend fun endSession(
        @Path("session_id") sessionId: String,
        @Body request: JsonObject
    ): NetworkResponse<EndSessionResponse, SessionsErrorResponse>

    /**
     * Create (no document_id) or update (with document_id) a document. The response carries a
     * pre-signed PUT URL for the content. Docs: /ekascribe/api-reference/documents/create-update-document
     */
    @POST("voice/api/v1/documents")
    suspend fun upsertDocument(
        @Body request: JsonObject
    ): NetworkResponse<DocumentEnvelopeResponse, SessionsErrorResponse>

    /**
     * "List Session Documents": the session's notes with their current names (`document_name`,
     * renames included), which [getSession] does not return. Used only for note titles; content
     * still comes from [getSession]. Spec: developer.eka.care/ekascribe/api-reference/openapi.yaml
     */
    @GET("voice/api/v1/sessions/{session_id}")
    suspend fun listSessionDocuments(
        @Path("session_id") sessionId: String
    ): NetworkResponse<JsonObject, SessionsErrorResponse>

    /**
     * Process Template: generate another note for an ended session (async). Returns the new
     * `document_id`; poll [getSessionDocument] until it is done.
     * Docs: /ekascribe/api-reference/sessions/process-template
     */
    @POST("voice/v1/sessions/{session_id}/process/template/{template_id}")
    suspend fun processTemplate(
        @Path("session_id") sessionId: String,
        @Path("template_id") templateId: String
    ): NetworkResponse<ProcessTemplateResponse, SessionsErrorResponse>

    /**
     * Get Session Audio: a fresh presigned URL for the combined recording (MP3). 403
     * `audio_not_enabled` when full-audio access is off for the business; 404 `audio_not_available`
     * while it is still being combined (or after retention), `resource_not_found` for unknown sessions.
     * Docs: developer.eka.care/ekascribe/api-reference/openapi.yaml
     */
    @GET("voice/v1/sessions/{session_id}/audio")
    suspend fun getSessionAudio(
        @Path("session_id") sessionId: String
    ): NetworkResponse<SessionAudioResponse, SessionsErrorResponse>

    /** Get Session filtered to one document (documented `document_id` query parameter). */
    @GET("voice/v1/sessions/{session_id}")
    suspend fun getSessionDocument(
        @Path("session_id") sessionId: String,
        @Query("document_id") documentId: String
    ): NetworkResponse<GetSessionResponse, SessionsErrorResponse>

    /** 202 = still processing, 200 = completed, 206 = partial; 410 (expired) arrives as ServerError. */
    @GET("voice/v1/sessions/{session_id}")
    suspend fun getSession(
        @Path("session_id") sessionId: String
    ): NetworkResponse<GetSessionResponse, SessionsErrorResponse>
}
