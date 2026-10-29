package com.eka.scribesdk.data.remote.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.remote.models.responses.SessionsApiError
import com.eka.scribesdk.data.remote.models.responses.SessionsErrorResponse
import com.eka.scribesdk.data.remote.models.responses.UploadAudioResponse
import com.eka.scribesdk.data.remote.services.SessionsApiService
import com.google.gson.JsonObject
import com.haroldadmin.cnradapter.NetworkResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response

internal class SessionsChunkUploaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val logger = object : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    @Test
    fun `upload posts raw bytes as audio_index_ext`() = runTest {
        val api = FakeUploadApi().apply { responses += uploaded() }
        val file = tempFolder.newFile("local-name.mp3").apply { writeText("abc") }

        val result = uploader(api).upload(file, metadata(chunkIndex = 0))

        assertTrue(result is UploadResult.Success)
        assertEquals("session-1/audio_0.mp3", (result as UploadResult.Success).url)
        val (sessionId, fileSequence, body) = api.calls.single()
        assertEquals("session-1", sessionId)
        assertEquals("audio_0.mp3", fileSequence)
        assertNull(body.contentType())
        assertEquals("abc", body.readUtf8())
    }

    @Test
    fun `upload keeps the file extension`() = runTest {
        val api = FakeUploadApi().apply { responses += uploaded() }
        val file = tempFolder.newFile("clip.wav").apply { writeText("wav") }

        uploader(api).upload(file, metadata(chunkIndex = 2, fileName = "clip.wav"))

        assertEquals("audio_2.wav", api.calls.single().second)
    }

    @Test
    fun `upload defaults a missing extension to mp3`() = runTest {
        val api = FakeUploadApi().apply { responses += uploaded() }
        val file = tempFolder.newFile("chunk").apply { writeText("x") }

        uploader(api).upload(file, metadata(chunkIndex = 1, fileName = "chunk"))

        assertEquals("audio_1.mp3", api.calls.single().second)
    }

    @Test
    fun `upload rejects a full recording without calling the api`() = runTest {
        val api = FakeUploadApi()
        val file = tempFolder.newFile("full.mp3")

        val result = uploader(api).upload(file, metadata(chunkIndex = -1))

        assertTrue(api.calls.isEmpty())
        val failure = result as UploadResult.Failure
        assertEquals("Full audio upload is not supported by the Sessions API", failure.error)
        assertTrue(!failure.isRetryable)
    }

    @Test
    fun `upload fails without retry when the file is missing`() = runTest {
        val api = FakeUploadApi().apply { responses += uploaded() }
        val file = tempFolder.newFile("gone.mp3").apply { delete() }

        val result = uploader(api).upload(file, metadata())

        assertTrue(api.calls.isEmpty())
        val failure = result as UploadResult.Failure
        assertEquals("File does not exist", failure.error)
        assertTrue(!failure.isRetryable)
    }

    @Test
    fun `upload skips the api when there is no network`() = runTest {
        val api = FakeUploadApi()
        val file = tempFolder.newFile("clip.mp3")

        val result = uploader(api, online = false).upload(file, metadata())

        assertTrue(api.calls.isEmpty())
        val failure = result as UploadResult.Failure
        assertEquals("No network available", failure.error)
        assertTrue(failure.isRetryable)
    }

    @Test
    fun `upload retries a server error then succeeds`() = runTest {
        val api = FakeUploadApi().apply {
            responses += serverError("busy", 500)
            responses += uploaded()
        }
        val file = tempFolder.newFile("clip.mp3").apply { writeText("a") }

        val result = uploader(api).upload(file, metadata())

        assertTrue(result is UploadResult.Success)
        assertEquals(2, api.calls.size)
        assertEquals("audio_0.mp3", api.calls[0].second)
        assertEquals("audio_0.mp3", api.calls[1].second)
    }

    @Test
    fun `upload retries http 429`() = runTest {
        val api = FakeUploadApi().apply {
            responses += serverError("slow down", 429)
            responses += uploaded()
        }
        val file = tempFolder.newFile("clip.mp3").apply { writeText("a") }

        val result = uploader(api).upload(file, metadata())

        assertTrue(result is UploadResult.Success)
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `upload does not retry a client error`() = runTest {
        val api = FakeUploadApi().apply { responses += serverError("bad audio", 400) }
        val file = tempFolder.newFile("clip.mp3").apply { writeText("a") }

        val result = uploader(api).upload(file, metadata())

        assertEquals(1, api.calls.size)
        val failure = result as UploadResult.Failure
        assertEquals("bad audio", failure.error)
        assertTrue(!failure.isRetryable)
    }

    private fun uploader(api: FakeUploadApi, online: Boolean = true) = SessionsChunkUploader(
        context = if (online) onlineContext() else offlineContext(),
        apiService = api,
        logger = logger
    )

    private fun metadata(chunkIndex: Int = 0, fileName: String = "1.mp3") = UploadMetadata(
        chunkId = "chunk-1",
        sessionId = "session-1",
        chunkIndex = chunkIndex,
        fileName = fileName,
        folderName = "260305",
        bid = "bid",
        mimeType = "audio/mpeg"
    )

    private fun uploaded(): NetworkResponse<UploadAudioResponse, SessionsErrorResponse> =
        netSuccess(UploadAudioResponse(success = true))

    private fun serverError(message: String, code: Int): NetworkResponse<UploadAudioResponse, SessionsErrorResponse> =
        NetworkResponse.ServerError(
            SessionsErrorResponse(error = SessionsApiError(code = "error", message = message), message = null),
            Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType()))
        )

    private fun <S> netSuccess(body: S): NetworkResponse<S, SessionsErrorResponse> {
        val raw = okhttp3.Response.Builder()
            .code(200)
            .protocol(Protocol.HTTP_1_1)
            .message("OK")
            .request(Request.Builder().url("http://test").build())
            .build()
        return NetworkResponse.Success(body, Response.success(body, raw))
    }

    private fun onlineContext(): Context {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns network
        every { connectivityManager.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
        return context
    }

    private fun offlineContext(): Context {
        val context = mockk<Context>()
        val connectivityManager = mockk<ConnectivityManager>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns null
        return context
    }

    private fun RequestBody.readUtf8(): String {
        val buffer = Buffer()
        writeTo(buffer)
        return buffer.readUtf8()
    }

    private class FakeUploadApi : SessionsApiService {
        val calls = mutableListOf<Triple<String, String, RequestBody>>()
        val responses = ArrayDeque<NetworkResponse<UploadAudioResponse, SessionsErrorResponse>>()

        override suspend fun uploadAudio(
            sessionId: String,
            fileSequence: String,
            audio: RequestBody
        ): NetworkResponse<UploadAudioResponse, SessionsErrorResponse> {
            calls += Triple(sessionId, fileSequence, audio)
            return if (responses.size > 1) responses.removeFirst() else responses.first()
        }

        override suspend fun createSession(request: JsonObject) = error("not used")
        override suspend fun patchSession(sessionId: String, request: JsonObject) = error("not used")
        override suspend fun endSession(sessionId: String, request: JsonObject) = error("not used")
        override suspend fun upsertDocument(request: JsonObject) = error("not used")
        override suspend fun getDocument(documentId: String) = error("not used")
        override suspend fun listSessionDocuments(sessionId: String) = error("not used")
        override suspend fun processTemplate(sessionId: String, templateId: String) = error("not used")
        override suspend fun getSessionAudio(sessionId: String) = error("not used")
        override suspend fun getSessionDocument(sessionId: String, documentId: String) = error("not used")
        override suspend fun getSession(sessionId: String) = error("not used")
    }
}
