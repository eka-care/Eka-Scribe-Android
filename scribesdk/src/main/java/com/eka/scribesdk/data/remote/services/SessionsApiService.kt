package com.eka.scribesdk.data.remote.services

import com.eka.scribesdk.data.remote.models.responses.CreateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.EndSessionResponse
import com.eka.scribesdk.data.remote.models.responses.PatchSessionResponse
import com.eka.scribesdk.data.remote.models.responses.SessionsErrorResponse
import com.eka.scribesdk.data.remote.models.responses.UploadAudioResponse
import com.google.gson.JsonObject
import com.haroldadmin.cnradapter.NetworkResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

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
}
