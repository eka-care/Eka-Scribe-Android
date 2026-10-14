package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.OutputTemplate
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.common.logging.NoOpLogger
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.ApiFlow
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.responses.CreateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.DocumentEnvelopeResponse
import com.eka.scribesdk.data.remote.models.responses.EndSessionResponse
import com.eka.scribesdk.data.remote.models.responses.GetSessionResponse
import com.eka.scribesdk.data.remote.models.responses.PatchSessionResponse
import com.eka.scribesdk.data.remote.models.responses.SessionsApiError
import com.eka.scribesdk.data.remote.models.responses.SessionsErrorResponse
import com.eka.scribesdk.data.remote.models.responses.UploadAudioResponse
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.haroldadmin.cnradapter.NetworkResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

internal class SessionsApiFlowTest {

    companion object {
        private const val SESSION_ID = "a-0123456789abcdef0123456789abcd"
    }

    // ---- Response builders ----

    private fun <T> okResponse(body: T, code: Int): Response<T> {
        val raw = okhttp3.Response.Builder()
            .code(code)
            .protocol(Protocol.HTTP_1_1)
            .message("OK")
            .request(Request.Builder().url("http://test").build())
            .build()
        return Response.success(body, raw)
    }

    private fun <S, E> netSuccess(body: S, code: Int = 200): NetworkResponse<S, E> =
        NetworkResponse.Success(body, okResponse(body, code))

    private fun <S> netServerError(message: String, code: Int): NetworkResponse<S, SessionsErrorResponse> =
        NetworkResponse.ServerError(
            SessionsErrorResponse(error = SessionsApiError(code = "error", message = message), message = null),
            Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
        )

    // ---- Fakes ----

    private class FakeSessionsApi : SessionsApiService {
        val createBodies = mutableListOf<JsonObject>()
        val patchBodies = mutableListOf<JsonObject>()
        val endBodies = mutableListOf<JsonObject>()

        var createResponse: NetworkResponse<CreateSessionResponse, SessionsErrorResponse>? = null
        var patchResponse: NetworkResponse<PatchSessionResponse, SessionsErrorResponse>? = null
        var endResponse: NetworkResponse<EndSessionResponse, SessionsErrorResponse>? = null
        val getResponses = ArrayDeque<NetworkResponse<GetSessionResponse, SessionsErrorResponse>>()
        var getCalls = 0
        val upsertBodies = mutableListOf<JsonObject>()
        var upsertResponse: NetworkResponse<DocumentEnvelopeResponse, SessionsErrorResponse>? = null

        override suspend fun createSession(request: JsonObject) =
            createResponse!!.also { createBodies += request }

        override suspend fun patchSession(sessionId: String, request: JsonObject) =
            patchResponse!!.also { patchBodies += request }

        override suspend fun uploadAudio(sessionId: String, fileSequence: String, audio: RequestBody):
            NetworkResponse<UploadAudioResponse, SessionsErrorResponse> = error("not used")

        override suspend fun endSession(sessionId: String, request: JsonObject) =
            endResponse!!.also { endBodies += request }

        override suspend fun upsertDocument(request: JsonObject) =
            upsertResponse!!.also { upsertBodies += request }

        override suspend fun getSession(sessionId: String): NetworkResponse<GetSessionResponse, SessionsErrorResponse> {
            getCalls++
            return if (getResponses.size > 1) getResponses.removeFirst() else getResponses.first()
        }
    }

    private fun createdOk(id: String = SESSION_ID) = netSuccess<CreateSessionResponse, SessionsErrorResponse>(
        CreateSessionResponse(id, "created", null, "backend", null, null), code = 201
    )

    private fun patchOk() = netSuccess<PatchSessionResponse, SessionsErrorResponse>(
        PatchSessionResponse(SESSION_ID, "created", null)
    )

    private fun sessionsEntity(stage: TransactionStage = TransactionStage.INIT) = SessionEntity(
        sessionId = SESSION_ID,
        createdAt = 0L,
        updatedAt = 0L,
        state = "RECORDING",
        uploadStage = stage.name,
        apiFlow = ApiFlow.SESSIONS_V1
    )

    private fun uploadedChunk(index: Int) = AudioChunkEntity(
        chunkId = "${SESSION_ID}_$index",
        sessionId = SESSION_ID,
        chunkIndex = index,
        filePath = "/tmp/$index.mp3",
        fileName = "${index + 1}.mp3",
        startTimeMs = 0L,
        endTimeMs = 0L,
        durationMs = 0L,
        uploadState = "SUCCESS",
        createdAt = 0L
    )

    private fun config() = SessionConfig(
        languages = listOf("en-IN", "hi", "en-US"),
        mode = "consultation",
        modelType = "lite",
        outputTemplates = listOf(
            OutputTemplate(templateId = "t1", templateName = "One"),
            OutputTemplate(templateId = "t2", templateName = "Two"),
            OutputTemplate(templateId = "t3", templateName = "Three")
        ),
        patientDetails = PatientDetail(age = 42, biologicalSex = "F", name = "Asha", patientId = "oid-1", visitId = "v-9"),
        speciality = "cardiology"
    )

    private fun flow(api: FakeSessionsApi, dm: DataManager) =
        SessionsApiFlow(apiService = api, dataManager = dm, flavour = "android", logger = NoOpLogger())

    private fun flowWithResults(api: FakeSessionsApi, dm: DataManager) = SessionsApiFlow(
        apiService = api,
        dataManager = dm,
        flavour = "android",
        logger = NoOpLogger(),
        resultFetcher = SessionsResultFetcher(api, NoOpLogger())
    )

    // Failed templates and no transcript: the adapter needs no base64, so it runs on the JVM
    private fun failedOnlyBody() = GetSessionResponse(
        sessionId = SESSION_ID, status = "completed", createdAt = null, languageDetected = null,
        transcript = null, message = null,
        templates = JsonParser.parseString("""[{"soap": {"status": "failure", "document_id": "d1"}}]""")
    )

    private fun processingBody() = GetSessionResponse(
        sessionId = SESSION_ID, status = "processing", createdAt = null, languageDetected = null,
        transcript = null, message = null, templates = null
    )

    // ---- createSession ----

    @Test
    fun `createSession sends documented body, patches session_mode and moves to STOP`() = runTest {
        val api = FakeSessionsApi().apply { createResponse = createdOk(); patchResponse = patchOk() }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flow(api, dm).createSession(SESSION_ID, config())

        assertTrue(result is TransactionResult.Success)
        val body = api.createBodies.single()
        assertEquals("chunked", body["upload_type"].asString)
        assertEquals(SESSION_ID, body["session_id"].asString)
        assertEquals("lite", body["model"].asString)
        assertEquals(listOf("t1", "t2"), body["templates"].asJsonArray.map { it.asString })
        assertEquals(listOf("en", "hi"), body["language_hint"].asJsonArray.map { it.asString })
        val patient = body["patient_details"].asJsonObject
        assertEquals("oid-1", patient["oid"].asString)
        assertEquals("42", patient["age"].asString)
        assertEquals("F", patient["gender"].asString)
        val additional = body["additional_data"].asJsonObject
        assertEquals("android", additional["_flavour"].asString)
        assertEquals("cardiology", additional["speciality"].asString)
        assertEquals("v-9", additional["visit_id"].asString)
        assertTrue("null section must be omitted", !additional.has("section"))

        val patch = api.patchBodies.single()
        assertEquals("consultation", patch["session_mode"].asString)
        assertEquals(1, patch.size())
        coVerify { dm.updateUploadStage(SESSION_ID, TransactionStage.STOP.name) }
    }

    @Test
    fun `createSession fails when server returns a different session id`() = runTest {
        val api = FakeSessionsApi().apply { createResponse = createdOk("other-id"); patchResponse = patchOk() }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flow(api, dm).createSession(SESSION_ID, config())

        assertTrue(result is TransactionResult.Error)
        assertTrue(api.patchBodies.isEmpty())
        coVerify(exactly = 0) { dm.updateUploadStage(any(), any()) }
    }

    @Test
    fun `createSession treats 409 as already created and continues`() = runTest {
        val api = FakeSessionsApi().apply {
            createResponse = netServerError("exists", 409)
            patchResponse = patchOk()
        }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flow(api, dm).createSession(SESSION_ID, config())

        assertTrue(result is TransactionResult.Success)
        assertEquals(1, api.patchBodies.size)
    }

    // ---- endSession ----

    @Test
    fun `endSession sends uploaded count and moves to COMMIT`() = runTest {
        val api = FakeSessionsApi().apply {
            endResponse = netSuccess(EndSessionResponse(SESSION_ID, "processing", null, 2, null), code = 202)
        }
        val dm = mockk<DataManager>(relaxed = true)
        coEvery { dm.getUploadedChunks(SESSION_ID) } returns listOf(uploadedChunk(0), uploadedChunk(1))

        val result = flow(api, dm).endSession(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        assertEquals(2, api.endBodies.single()["audio_files_sent"].asInt)
        coVerify { dm.updateUploadStage(SESSION_ID, TransactionStage.COMMIT.name) }
    }

    @Test
    fun `endSession treats already-ended as success`() = runTest {
        val api = FakeSessionsApi().apply { endResponse = netServerError("Session already ended", 400) }
        val dm = mockk<DataManager>(relaxed = true)
        coEvery { dm.getUploadedChunks(SESSION_ID) } returns emptyList()

        val result = flow(api, dm).endSession(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        coVerify { dm.updateUploadStage(SESSION_ID, TransactionStage.COMMIT.name) }
    }

    // ---- TransactionManager routing ----

    @Test
    fun `TransactionManager routes Sessions API sessions and skips commit`() = runTest {
        val api = FakeSessionsApi().apply {
            endResponse = netSuccess(EndSessionResponse(SESSION_ID, "processing", null, 0, null), code = 202)
        }
        val dm = mockk<DataManager>(relaxed = true)
        coEvery { dm.getSession(SESSION_ID) } returns sessionsEntity(TransactionStage.STOP)
        coEvery { dm.getUploadedChunks(SESSION_ID) } returns emptyList()
        // Strict mock: any legacy API call fails the test
        val legacyApi = mockk<ScribeApiService>()
        val manager = TransactionManager(
            apiService = legacyApi,
            dataManager = dm,
            chunkUploader = mockk<ChunkUploader>(relaxed = true),
            bucketName = "bucket",
            maxUploadRetries = 2,
            pollMaxRetries = 1,
            pollDelayMs = 0L,
            logger = NoOpLogger(),
            sessionsApiFlow = flow(api, dm),
            useSessionsApi = true
        )

        assertTrue(manager.stopTransaction(SESSION_ID) is TransactionResult.Success)
        assertTrue(manager.commitTransaction(SESSION_ID) is TransactionResult.Success)

        assertEquals(1, api.endBodies.size)
        coVerify { dm.updateUploadStage(SESSION_ID, TransactionStage.ANALYZING.name) }
    }

    // ---- pollResult ----

    @Test
    fun `pollResult keeps polling on 202 and fails when processing produced no output`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netSuccess(processingBody(), code = 202))
            getResponses.addLast(netSuccess(failedOnlyBody(), code = 200))
        }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flowWithResults(api, dm).pollResult(SESSION_ID)

        assertEquals(2, api.getCalls)
        assertTrue(result is TransactionPollResult.Failed)
        coVerify { dm.updateUploadStage(SESSION_ID, TransactionStage.FAILURE.name) }
    }

    @Test
    fun `pollResult maps 410 to an expired failure`() = runTest {
        val api = FakeSessionsApi().apply { getResponses.addLast(netServerError("expired", 410)) }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flowWithResults(api, dm).pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Failed)
        assertEquals("Session expired before processing", (result as TransactionPollResult.Failed).error)
        assertEquals(1, api.getCalls)
    }

    @Test
    fun `pollResult stops on a non-retryable error`() = runTest {
        val api = FakeSessionsApi().apply { getResponses.addLast(netServerError("not found", 404)) }
        val dm = mockk<DataManager>(relaxed = true)

        val result = flowWithResults(api, dm).pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Failed)
        assertEquals(1, api.getCalls)
    }

    // ---- SessionsDocumentWriter ----

    private fun uploadUrlOk(url: String = "https://s3.example/put") =
        netSuccess<DocumentEnvelopeResponse, SessionsErrorResponse>(
            DocumentEnvelopeResponse("success", DocumentEnvelopeResponse.DocumentData("doc-1", SESSION_ID, "success", url))
        )

    @Test
    fun `document writer gets an upload url and PUTs the base64 content`() = runTest {
        val api = FakeSessionsApi().apply { upsertResponse = uploadUrlOk() }
        val puts = mutableListOf<Pair<String, String>>()
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { url, body -> puts += url to body; true }

        val result = writer.save(SESSION_ID, listOf(SessionData(templateId = "t1", documentId = "doc-1", data = "SGVsbG8=")))

        assertTrue(result.isSuccess)
        val body = api.upsertBodies.single()
        assertEquals(SESSION_ID, body["session_id"].asString)
        assertEquals("doc-1", body["document_id"].asString)
        assertEquals(listOf("https://s3.example/put" to "SGVsbG8="), puts)
    }

    @Test
    fun `document writer refreshes the url once when the PUT fails`() = runTest {
        val api = FakeSessionsApi().apply { upsertResponse = uploadUrlOk() }
        var attempts = 0
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { _, _ -> ++attempts == 2 }

        val result = writer.save(SESSION_ID, listOf(SessionData("t1", "doc-1", "SGVsbG8=")))

        assertTrue(result.isSuccess)
        assertEquals(2, api.upsertBodies.size)
    }

    @Test
    fun `document writer fails when the server refuses the update`() = runTest {
        val api = FakeSessionsApi().apply { upsertResponse = netServerError("Document not found", 404) }
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { _, _ -> true }

        val result = writer.save(SESSION_ID, listOf(SessionData("t1", "doc-1", "SGVsbG8=")))

        assertTrue(result.isFailure)
        assertEquals("Document not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `ApiFlow marks only Sessions API sessions`() {
        assertEquals(ApiFlow.SESSIONS_V1, ApiFlow.forNewSession(true))
        assertNull(ApiFlow.forNewSession(false))
    }
}
