package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.OutputTemplate
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.api.models.SessionAudioUnavailableException
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.common.logging.NoOpLogger
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.ScribeRepository
import com.eka.scribesdk.data.local.db.entity.ApiFlow
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.responses.CreateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.DocumentEnvelopeResponse
import com.eka.scribesdk.data.remote.models.responses.EndSessionResponse
import com.eka.scribesdk.data.remote.models.responses.GetSessionResponse
import com.eka.scribesdk.data.remote.models.responses.PatchSessionResponse
import com.eka.scribesdk.data.remote.models.responses.ProcessTemplateResponse
import com.eka.scribesdk.data.remote.models.responses.SessionAudioResponse
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
import java.io.IOException
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
        /** null = the call throws, like a network failure. */
        var documentsResponse: NetworkResponse<JsonObject, SessionsErrorResponse>? = null
        var processResponse: NetworkResponse<ProcessTemplateResponse, SessionsErrorResponse>? = null
        var audioResponse: NetworkResponse<SessionAudioResponse, SessionsErrorResponse>? = null
        var audioCalls = 0
        val processCalls = mutableListOf<Pair<String, String>>()
        val documentQueries = mutableListOf<String>()

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

        override suspend fun getSessionAudio(sessionId: String) =
            audioResponse!!.also { audioCalls++ }

        override suspend fun processTemplate(sessionId: String, templateId: String) =
            processResponse!!.also { processCalls += sessionId to templateId }

        override suspend fun getSessionDocument(sessionId: String, documentId: String) =
            getSession(sessionId).also { documentQueries += documentId }

        var getDocumentResponse: NetworkResponse<DocumentEnvelopeResponse, SessionsErrorResponse>? = null
        val getDocumentCalls = mutableListOf<String>()

        override suspend fun getDocument(documentId: String) =
            getDocumentResponse!!.also { getDocumentCalls += documentId }

        override suspend fun listSessionDocuments(sessionId: String) =
            documentsResponse ?: error("documents call failed")

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
        assertEquals("recording_started", patch["user_status"].asString)
        assertEquals(2, patch.size())
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
    fun `endSession reports audio_duration in additional_data like the web`() = runTest {
        val api = FakeSessionsApi().apply {
            endResponse = netSuccess(EndSessionResponse(SESSION_ID, "processing", null, 2, null), code = 202)
            patchResponse = patchOk()
        }
        val dm = mockk<DataManager>(relaxed = true)
        coEvery { dm.getUploadedChunks(SESSION_ID) } returns listOf(uploadedChunk(0), uploadedChunk(1))
        coEvery { dm.getAllChunks(SESSION_ID) } returns listOf(
            uploadedChunk(0).copy(durationMs = 20_000L),
            uploadedChunk(1).copy(durationMs = 12_600L)
        )

        val result = flow(api, dm).endSession(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        val additional = api.patchBodies.single()["additional_data"].asJsonObject
        assertEquals(33, additional["audio_duration"].asInt)
    }

    @Test
    fun `endSession still succeeds when audio_duration cannot be saved`() = runTest {
        val api = FakeSessionsApi().apply {
            endResponse = netSuccess(EndSessionResponse(SESSION_ID, "processing", null, 1, null), code = 202)
            patchResponse = netServerError("nope", 500)
        }
        val dm = mockk<DataManager>(relaxed = true)
        coEvery { dm.getAllChunks(SESSION_ID) } returns listOf(uploadedChunk(0).copy(durationMs = 5_000L))

        assertTrue(flow(api, dm).endSession(SESSION_ID) is TransactionResult.Success)
    }

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

    // ---- recording download (Get Session Audio) ----

    private fun audioOk() = netSuccess<SessionAudioResponse, SessionsErrorResponse>(
        SessionAudioResponse(SESSION_ID, "success", "https://s3.example/audio.mp3?sig", 86_400, 1_800_086_400)
    )

    private fun audioError(code: Int, errorCode: String) = NetworkResponse.ServerError<SessionAudioResponse, SessionsErrorResponse>(
        SessionsErrorResponse(error = SessionsApiError(code = errorCode, message = errorCode), message = null),
        Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
    )

    private fun downloader(
        api: FakeSessionsApi,
        streamed: MutableList<String> = mutableListOf(),
        succeeds: Boolean = true
    ) = SessionAudioDownloader(api, NoOpLogger()) { url, destination ->
        streamed += url
        destination.write("mp3-bytes".toByteArray())
        succeeds
    }

    private fun <T> Result<T>.reason() = (exceptionOrNull() as SessionAudioUnavailableException).reason

    @Test
    fun `link comes straight from Get Session Audio`() = runTest {
        val api = FakeSessionsApi().apply { audioResponse = audioOk() }

        val link = downloader(api).link(SESSION_ID).getOrThrow()

        assertEquals("https://s3.example/audio.mp3?sig", link.url)
        assertEquals(1_800_086_400_000L, link.expiresAtMillis)
        assertEquals(1, api.audioCalls)
        assertEquals(0, api.getCalls)
    }

    @Test
    fun `audio API errors map to clear reasons`() = runTest {
        val cases = listOf(
            audioError(403, "audio_not_enabled") to SessionAudioUnavailableException.Reason.NOT_ENABLED,
            audioError(404, "audio_not_available") to SessionAudioUnavailableException.Reason.NOT_READY,
            audioError(404, "resource_not_found") to SessionAudioUnavailableException.Reason.NOT_FOUND,
            audioError(410, "expired") to SessionAudioUnavailableException.Reason.EXPIRED
        )
        for ((response, reason) in cases) {
            val api = FakeSessionsApi().apply { audioResponse = response }
            assertEquals(reason, downloader(api).link(SESSION_ID).reason())
        }
    }

    @Test
    fun `download streams the recording into the destination`() = runTest {
        val api = FakeSessionsApi().apply { audioResponse = audioOk() }
        val streamed = mutableListOf<String>()
        val destination = java.io.ByteArrayOutputStream()

        val result = downloader(api, streamed).download(SESSION_ID, destination)

        assertTrue(result.isSuccess)
        assertEquals(listOf("https://s3.example/audio.mp3?sig"), streamed)
        assertEquals("mp3-bytes", destination.toString())
    }

    @Test
    fun `download fails cleanly when the transfer fails or there is no recording`() = runTest {
        val failing = FakeSessionsApi().apply { audioResponse = audioOk() }
        val failed = downloader(failing, succeeds = false).download(SESSION_ID, java.io.ByteArrayOutputStream())
        assertEquals(SessionAudioUnavailableException.Reason.FAILED, failed.reason())

        val missing = FakeSessionsApi().apply { audioResponse = audioError(404, "resource_not_found") }
        val streamed = mutableListOf<String>()
        val result = downloader(missing, streamed).download(SESSION_ID, java.io.ByteArrayOutputStream())
        assertEquals(SessionAudioUnavailableException.Reason.NOT_FOUND, result.reason())
        assertTrue(streamed.isEmpty())
    }

    // ---- convert to another template (Process Template) ----

    private fun processStarted(documentId: String? = "d9") = netSuccess<ProcessTemplateResponse, SessionsErrorResponse>(
        ProcessTemplateResponse(SESSION_ID, "t-9", documentId, "in-progress", "Template generation in progress"),
        code = 202
    )

    private fun documentBody(status: String, documentId: String = "d9") = GetSessionResponse(
        sessionId = SESSION_ID, status = "completed", createdAt = null, languageDetected = null,
        transcript = null, message = null,
        templates = JsonParser.parseString("""[{"t-9": {"status": "$status", "document_id": "$documentId"}}]""")
    )

    private fun processor(api: FakeSessionsApi, maxAttempts: Int = 5) =
        SessionsTemplateProcessor(api, NoOpLogger(), maxAttempts = maxAttempts, intervalMs = 10)

    @Test
    fun `convert starts Process Template and waits for the new note`() = runTest {
        val api = FakeSessionsApi().apply {
            processResponse = processStarted()
            getResponses.addLast(netSuccess(processingBody(), code = 202))
            getResponses.addLast(netSuccess(documentBody("in-progress")))
            getResponses.addLast(netSuccess(documentBody("success")))
        }

        val result = processor(api).process(SESSION_ID, "t-9")

        assertTrue(result.isSuccess)
        assertEquals(listOf(SESSION_ID to "t-9"), api.processCalls)
        assertEquals(listOf("d9", "d9", "d9"), api.documentQueries)
    }

    @Test
    fun `convert polls the whole session when no document id comes back`() = runTest {
        val api = FakeSessionsApi().apply {
            processResponse = processStarted(documentId = null)
            getResponses.addLast(netSuccess(documentBody("success")))
        }

        val result = processor(api).process(SESSION_ID, "t-9")

        assertTrue(result.isSuccess)
        assertTrue(api.documentQueries.isEmpty())
        assertEquals(1, api.getCalls)
    }

    @Test
    fun `convert fails when the new note fails`() = runTest {
        val api = FakeSessionsApi().apply {
            processResponse = processStarted()
            getResponses.addLast(netSuccess(documentBody("failure")))
        }

        val result = processor(api).process(SESSION_ID, "t-9")

        assertTrue(result.isFailure)
        assertEquals(1, api.getCalls)
    }

    @Test
    fun `convert fails without polling when Process Template is refused`() = runTest {
        val api = FakeSessionsApi().apply { processResponse = netServerError("Session not found", 404) }

        val result = processor(api).process(SESSION_ID, "t-9")

        assertEquals("Session not found", result.exceptionOrNull()?.message)
        assertEquals(0, api.getCalls)
    }

    @Test
    fun `convert keeps polling through server errors and gives up after the budget`() = runTest {
        val api = FakeSessionsApi().apply {
            processResponse = processStarted()
            getResponses.addLast(netServerError("busy", 503))
            getResponses.addLast(netSuccess(documentBody("in-progress")))
        }

        val result = processor(api, maxAttempts = 3).process(SESSION_ID, "t-9")

        assertTrue(result.isFailure)
        assertEquals(3, api.getCalls)
    }

    @Test
    fun `repository converts Sessions API sessions with Process Template, legacy with convert-to-template`() = runTest {
        val api = FakeSessionsApi().apply {
            processResponse = processStarted()
            getResponses.addLast(netSuccess(documentBody("success")))
        }
        val legacyApi = mockk<ScribeApiService>()
        coEvery { legacyApi.convertTransactionResult("legacy-session", "t-9") } returns
            NetworkResponse.NetworkError(IOException("legacy endpoint called"))
        val repository = ScribeRepository(
            apiService = legacyApi,
            logger = NoOpLogger(),
            sessionApiRouter = SessionApiRouter(
                sessionsApiEnabled = false,
                recordedOnSessionsApi = { sessionId -> if (sessionId == SESSION_ID) true else null }
            ),
            sessionsTemplateProcessor = processor(api)
        )

        assertTrue(repository.convertTransactionResult(SESSION_ID, "t-9").isSuccess)
        assertEquals(listOf(SESSION_ID to "t-9"), api.processCalls)

        val legacy = repository.convertTransactionResult("legacy-session", "t-9")
        assertEquals("legacy endpoint called", legacy.exceptionOrNull()?.message)
        assertEquals(1, api.processCalls.size)
    }

    // ---- sessions recorded on another client (web, extension, another phone) ----

    @Test
    fun `repository reads a session from another client on the Sessions API when enabled`() = runTest {
        val api = FakeSessionsApi().apply { getResponses.addLast(netSuccess(documentBody("success"))) }
        val repository = ScribeRepository(
            apiService = mockk(),
            logger = NoOpLogger(),
            sessionsResultFetcher = SessionsResultFetcher(api, NoOpLogger()),
            sessionApiRouter = SessionApiRouter(sessionsApiEnabled = true, recordedOnSessionsApi = { null })
        )

        assertTrue(repository.getSessionOutput("sc-web0123456789abcdef0123456789").isSuccess)
        assertEquals(1, api.getCalls)
    }

    @Test
    fun `repository falls back to legacy when the Sessions API does not know a remote session`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netServerError("not found", 404))
            processResponse = processStarted()
        }
        val legacyApi = mockk<ScribeApiService>()
        coEvery { legacyApi.getTransactionResult("old-session", null) } returns
            NetworkResponse.NetworkError(IOException("legacy read called"))
        coEvery { legacyApi.convertTransactionResult("old-session", "t-9") } returns
            NetworkResponse.NetworkError(IOException("legacy convert called"))
        val repository = ScribeRepository(
            apiService = legacyApi,
            logger = NoOpLogger(),
            sessionsResultFetcher = SessionsResultFetcher(api, NoOpLogger()),
            sessionApiRouter = SessionApiRouter(sessionsApiEnabled = true, recordedOnSessionsApi = { null }),
            sessionsTemplateProcessor = processor(api)
        )

        val read = repository.getSessionOutput("old-session")
        assertEquals("legacy read called", read.exceptionOrNull()?.message)

        // Remembered: convert for the same session goes to legacy without asking the Sessions API again
        val convert = repository.convertTransactionResult("old-session", "t-9")
        assertEquals("legacy convert called", convert.exceptionOrNull()?.message)
        assertTrue(api.processCalls.isEmpty())
        assertEquals(1, api.getCalls)
    }

    @Test
    fun `repository does not fall back to legacy for a Sessions API session recorded on this device`() = runTest {
        val api = FakeSessionsApi().apply { getResponses.addLast(netServerError("not found", 404)) }
        val repository = ScribeRepository(
            apiService = mockk(),
            logger = NoOpLogger(),
            sessionsResultFetcher = SessionsResultFetcher(api, NoOpLogger()),
            sessionApiRouter = SessionApiRouter(sessionsApiEnabled = true, recordedOnSessionsApi = { true })
        )

        val read = repository.getSessionOutput(SESSION_ID)
        assertEquals("not found", read.exceptionOrNull()?.message)
    }

    // ---- note titles ----

    private fun fetcher(api: FakeSessionsApi, templateNameCalls: MutableList<List<String>>) =
        SessionsResultFetcher(api, NoOpLogger()) { _, ids ->
            templateNameCalls += ids
            mapOf("soap" to "SOAP Note")
        }

    private fun SessionFetchState?.noteName(): String? =
        (this as SessionFetchState.Done).result.data!!.templateResults!!.custom!!.single()!!.name

    @Test
    fun `fetch titles notes with their own name and skips the template lookup`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netSuccess(failedOnlyBody()))
            documentsResponse = netSuccess(
                JsonParser.parseString(
                    """{"status": "success", "data": {"documents": [{"document_id": "d1", "document_name": "Renamed note"}]}}"""
                ).asJsonObject
            )
        }
        val templateNameCalls = mutableListOf<List<String>>()

        val name = fetcher(api, templateNameCalls).fetch(SESSION_ID).noteName()

        assertEquals("Renamed note", name)
        assertTrue(templateNameCalls.isEmpty())
    }

    @Test
    fun `fetch falls back to template names when the documents call fails`() = runTest {
        val api = FakeSessionsApi().apply { getResponses.addLast(netSuccess(failedOnlyBody())) }
        val templateNameCalls = mutableListOf<List<String>>()

        val name = fetcher(api, templateNameCalls).fetch(SESSION_ID).noteName()

        assertEquals("SOAP Note", name)
        assertEquals(listOf(listOf("soap")), templateNameCalls)
    }

    @Test
    fun `fetch falls back to template names for notes the documents call does not name`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netSuccess(failedOnlyBody()))
            documentsResponse = netSuccess(
                JsonParser.parseString("""{"status": "success", "data": {"documents": []}}""").asJsonObject
            )
        }

        val name = fetcher(api, mutableListOf()).fetch(SESSION_ID).noteName()

        assertEquals("SOAP Note", name)
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

    private fun publishedDocOk() = netSuccess<DocumentEnvelopeResponse, SessionsErrorResponse>(
        DocumentEnvelopeResponse(
            "success",
            DocumentEnvelopeResponse.DocumentData(
                "doc-1", SESSION_ID, "success", "https://s3.example/put",
                publish = JsonParser.parseString("""{"emr_webhook": {"status": "success"}}""").asJsonObject
            )
        )
    )

    // ---- context (typed on the web's Context tab) ----

    private object JvmCodec : com.eka.scribesdk.data.remote.models.responses.Base64Codec {
        override fun encode(text: String): String =
            java.util.Base64.getEncoder().encodeToString(text.toByteArray(Charsets.UTF_8))

        override fun decode(base64: String): ByteArray? =
            try {
                java.util.Base64.getMimeDecoder().decode(base64)
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    private fun contextDocOk(url: String = "https://s3.example/context") =
        netSuccess<DocumentEnvelopeResponse, SessionsErrorResponse>(
            DocumentEnvelopeResponse(
                "success",
                DocumentEnvelopeResponse.DocumentData("ctx-1", SESSION_ID, "success", url, documentType = "context")
            )
        )

    private fun sessionWithContextBody() = GetSessionResponse(
        sessionId = SESSION_ID,
        status = "completed",
        createdAt = null,
        languageDetected = null,
        transcript = null,
        templates = JsonParser.parseString(
            """[{"soap": {"status": "success", "document_id": "d1", "document_type": "custom", "data": "Fever 3 days"}},
                {"__non_tmp_doc": {"status": "success", "document_id": "ctx-1", "document_type": "context"}}]"""
        ),
        message = null
    )

    @Test
    fun `fetch shows the context after the notes, read from its download link`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netSuccess(sessionWithContextBody()))
            getDocumentResponse = contextDocOk()
        }
        val downloads = mutableListOf<String>()
        val reader = SessionContextReader(api, NoOpLogger()) { url -> downloads += url; "Known diabetic, on metformin" }

        val state = SessionsResultFetcher(api, NoOpLogger(), contextReader = reader, codec = JvmCodec).fetch(SESSION_ID)

        val notes = (state as SessionFetchState.Done).result.data!!.templateResults!!.custom!!
        assertEquals(listOf("d1", "ctx-1"), notes.map { it!!.documentId })
        val context = notes[1]!!
        assertEquals("Context", context.name)
        assertEquals("__non_tmp_doc", context.templateId)
        assertEquals(listOf("ctx-1"), api.getDocumentCalls)
        assertEquals(listOf("https://s3.example/context"), downloads)
    }

    @Test
    fun `fetch shows the context read-only and empty when it cannot be downloaded`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(netSuccess(sessionWithContextBody()))
            getDocumentResponse = contextDocOk()
        }
        val reader = SessionContextReader(api, NoOpLogger()) { null }

        val state = SessionsResultFetcher(api, NoOpLogger(), contextReader = reader, codec = JvmCodec).fetch(SESSION_ID)

        val results = (state as SessionFetchState.Done).result.data!!.templateResults!!
        assertEquals(listOf("d1"), results.custom!!.map { it!!.documentId })
        assertEquals(listOf("ctx-1"), results.integration!!.map { it!!.documentId })
    }

    @Test
    fun `fetch shows an empty editable Context when the session has none`() = runTest {
        val api = FakeSessionsApi().apply {
            getResponses.addLast(
                netSuccess(
                    sessionWithContextBody().copy(
                        templates = JsonParser.parseString(
                            """[{"soap": {"status": "success", "document_id": "d1", "data": "Fever"}}]"""
                        )
                    )
                )
            )
        }
        val reader = SessionContextReader(api, NoOpLogger()) { error("nothing to download") }

        val state = SessionsResultFetcher(api, NoOpLogger(), contextReader = reader, codec = JvmCodec).fetch(SESSION_ID)

        val notes = (state as SessionFetchState.Done).result.data!!.templateResults!!.custom!!
        assertEquals(listOf("d1", "${SESSION_ID}_context"), notes.map { it!!.documentId })
        assertEquals("Context", notes[1]!!.name)
        assertTrue(api.getDocumentCalls.isEmpty())
    }

    @Test
    fun `saving the empty Context creates the context document once, then saves the text`() = runTest {
        val api = FakeSessionsApi().apply {
            upsertResponse = netSuccess(
                DocumentEnvelopeResponse(
                    "success",
                    DocumentEnvelopeResponse.DocumentData("ctx-new", SESSION_ID, "success", "https://s3.example/put")
                )
            )
        }
        val puts = mutableListOf<String>()
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { _, body -> puts += body; true }
        val placeholder = SessionData("__non_tmp_doc", "${SESSION_ID}_context", "S25vd24gZGlhYmV0aWM=")

        assertTrue(writer.save(SESSION_ID, listOf(placeholder)).isSuccess)
        assertTrue(writer.save(SESSION_ID, listOf(placeholder)).isSuccess)

        val creates = api.upsertBodies.filter { it.has("type") }
        assertEquals(1, creates.size)
        assertEquals("context", creates.single()["type"].asString)
        assertEquals("Context", creates.single()["document_name"].asString)
        assertNull(creates.single()["document_id"])
        assertTrue(api.upsertBodies.filterNot { it.has("type") }.all { it["document_id"].asString == "ctx-new" })
        assertEquals(listOf("S25vd24gZGlhYmV0aWM=", "S25vd24gZGlhYmV0aWM="), puts)
    }

    @Test
    fun `document writer unpublishes a note published to the EMR before saving`() = runTest {
        val api = FakeSessionsApi().apply { upsertResponse = publishedDocOk() }
        val puts = mutableListOf<String>()
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { url, _ -> puts += url; true }

        val result = writer.save(SESSION_ID, listOf(SessionData(templateId = "t1", documentId = "doc-1", data = "SGVsbG8=")))

        assertTrue(result.isSuccess)
        assertEquals(2, api.upsertBodies.size)
        assertNull(api.upsertBodies[0]["publish"])
        val webhook = api.upsertBodies[1]["publish"].asJsonObject["emr_webhook"].asJsonObject
        assertEquals("unpublished", webhook["status"].asString)
        assertEquals("doc-1", api.upsertBodies[1]["document_id"].asString)
        assertEquals(listOf("https://s3.example/put"), puts)
    }

    @Test
    fun `document writer leaves unpublished notes alone`() = runTest {
        val api = FakeSessionsApi().apply { upsertResponse = uploadUrlOk() }
        val writer = SessionsDocumentWriter(api, NoOpLogger()) { _, _ -> true }

        writer.save(SESSION_ID, listOf(SessionData(templateId = "t1", documentId = "doc-1", data = "SGVsbG8=")))

        assertEquals(1, api.upsertBodies.size)
    }

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
