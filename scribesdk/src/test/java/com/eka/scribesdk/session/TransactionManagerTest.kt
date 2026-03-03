package com.eka.scribesdk.session

import com.eka.scribesdk.api.models.OutputTemplate
import com.eka.scribesdk.api.models.PatientDetail
import com.eka.scribesdk.api.models.SessionConfig
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.DataManager
import com.eka.scribesdk.data.local.db.entity.AudioChunkEntity
import com.eka.scribesdk.data.local.db.entity.SessionEntity
import com.eka.scribesdk.data.local.db.entity.TransactionStage
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest
import com.eka.scribesdk.data.remote.models.responses.InitTransactionResponse
import com.eka.scribesdk.data.remote.models.responses.ResultStatus
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.StopTransactionResponse
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.eka.scribesdk.data.remote.upload.ChunkUploader
import com.eka.scribesdk.data.remote.upload.UploadMetadata
import com.eka.scribesdk.data.remote.upload.UploadResult
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import retrofit2.Response
import java.io.File
import java.io.IOException

internal class TransactionManagerTest {

    companion object {
        private const val SESSION_ID = "test-session-123"
        private const val BUCKET_NAME = "test-bucket"
        private const val MAX_RETRIES = 2
    }

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =====================================================================
    // RESPONSE BUILDERS
    // =====================================================================

    /** Wrap body in a retrofit2.Response with the given HTTP code. */
    private fun <T> okResponse(body: T, code: Int = 200): Response<T> {
        if (code == 200) return Response.success(body)
        // Non-200 2xx (e.g. 202)
        val raw = okhttp3.Response.Builder()
            .code(code)
            .protocol(Protocol.HTTP_1_1)
            .message("OK")
            .request(Request.Builder().url("http://test").build())
            .build()
        return Response.success(body, raw)
    }

    private fun errResponse(code: Int): Response<Nothing> {
        val body = "{}".toResponseBody("application/json".toMediaType())
        return Response.error(code, body)
    }

    private fun <S, E> netSuccess(body: S, code: Int = 200): NetworkResponse<S, E> =
        NetworkResponse.Success(body, okResponse(body, code))

    private fun <S, E> netServerError(body: E?, code: Int): NetworkResponse<S, E> =
        NetworkResponse.ServerError(body, errResponse(code))

    // =====================================================================
    // SETUP
    // =====================================================================

    private fun createManager(
        api: FakeApiService = FakeApiService(),
        dataManager: FakeDataManager = FakeDataManager(),
        uploader: FakeChunkUploader = FakeChunkUploader()
    ): Triple<TransactionManager, FakeApiService, FakeDataManager> {
        val manager = TransactionManager(
            apiService = api,
            dataManager = dataManager,
            chunkUploader = uploader,
            bucketName = BUCKET_NAME,
            maxUploadRetries = MAX_RETRIES,
            logger = NoOpLogger()
        )
        return Triple(manager, api, dataManager)
    }

    private fun defaultConfig() = SessionConfig(
        languages = listOf("en-IN"),
        mode = "dictation",
        modelType = "pro"
    )

    private fun initOk(bid: String = "bid-001") = InitTransactionResponse(
        bId = bid, message = "ok", status = "success", txnId = null, error = null
    )

    private fun stopOk() = StopTransactionResponse(
        message = "ok", status = "success", error = null
    )

    // =====================================================================
    // INIT TRANSACTION
    // =====================================================================

    @Test
    fun `initTransaction success updates stage and returns folderName and bid`() = runTest {
        val api = FakeApiService()
        val body = initOk("bid-001")
        api.initResponse = netSuccess(body)
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue("Should be Success", result is TransactionResult.Success)
        val success = result as TransactionResult.Success
        assertEquals("bid-001", success.bid)
        assertTrue("folderName should be date-based", success.folderName.length == 6)
        assertEquals(TransactionStage.STOP.name, dm.uploadStages[SESSION_ID])
        assertTrue(dm.folderBidUpdated)
    }

    @Test
    fun `initTransaction saves session metadata for recovery`() = runTest {
        val api = FakeApiService()
        api.initResponse = netSuccess(initOk("bid-002"))
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(dm.sessionMetadataMap.containsKey(SESSION_ID))
        assertTrue(dm.sessionMetadataMap[SESSION_ID]!!.contains("input_language"))
    }

    @Test
    fun `initTransaction server error returns Error`() = runTest {
        val api = FakeApiService()
        val errBody = InitTransactionResponse(
            bId = null, message = "Unauthorized", status = "error", txnId = null, error = null
        )
        api.initResponse = netServerError(errBody, 401)
        val (manager, _, _) = createManager(api = api)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Unauthorized"))
    }

    @Test
    fun `initTransaction network error returns Error`() = runTest {
        val api = FakeApiService()
        api.initResponse = NetworkResponse.NetworkError(IOException("Connection refused"))
        val (manager, _, _) = createManager(api = api)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Network error"))
    }

    @Test
    fun `initTransaction unknown error returns Error`() = runTest {
        val api = FakeApiService()
        api.initResponse = NetworkResponse.UnknownError(RuntimeException("Unexpected"), null)
        val (manager, _, _) = createManager(api = api)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Unknown error"))
    }

    @Test
    fun `initTransaction with null bId defaults to empty string`() = runTest {
        val api = FakeApiService()
        val body = InitTransactionResponse(
            bId = null, message = null, status = null, txnId = null, error = null
        )
        api.initResponse = netSuccess(body)
        val (manager, _, _) = createManager(api = api)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(result is TransactionResult.Success)
        assertEquals("", (result as TransactionResult.Success).bid)
    }

    // =====================================================================
    // STOP TRANSACTION
    // =====================================================================

    @Test
    fun `stopTransaction success builds chunkInfo with per-chunk timing`() = runTest {
        val api = FakeApiService()
        api.stopResponse = netSuccess(stopOk())
        val dm = FakeDataManager()
        dm.uploadedChunksList = listOf(
            makeChunkEntity("chunk-0", "1.m4a", startTimeMs = 0, endTimeMs = 14272),
            makeChunkEntity("chunk-1", "2.m4a", startTimeMs = 13772, endTimeMs = 25924)
        )
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        val request = api.lastStopRequest!!
        assertEquals(2, request.audioFiles.size)
        assertEquals("1.m4a", request.audioFiles[0])
        assertEquals("2.m4a", request.audioFiles[1])
        assertEquals(2, request.chunkInfo.size)
        assertEquals(TransactionStage.COMMIT.name, dm.uploadStages[SESSION_ID])
    }

    @Test
    fun `stopTransaction server error returns Error`() = runTest {
        val api = FakeApiService()
        val errBody = StopTransactionResponse(message = null, status = null, error = "Bad Request")
        api.stopResponse = netServerError(errBody, 400)
        val (manager, _, _) = createManager(api = api)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
    }

    @Test
    fun `stopTransaction network error returns Error`() = runTest {
        val api = FakeApiService()
        api.stopResponse = NetworkResponse.NetworkError(IOException("Timeout"))
        val (manager, _, _) = createManager(api = api)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Network error"))
    }

    // =====================================================================
    // COMMIT TRANSACTION
    // =====================================================================

    @Test
    fun `commitTransaction success updates stage to ANALYZING`() = runTest {
        val api = FakeApiService()
        api.commitResponse = netSuccess(stopOk())
        val dm = FakeDataManager()
        dm.uploadedChunksList = listOf(makeChunkEntity("c1", "1.m4a"))
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.commitTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        assertEquals(TransactionStage.ANALYZING.name, dm.uploadStages[SESSION_ID])
    }

    @Test
    fun `commitTransaction server error returns Error`() = runTest {
        val api = FakeApiService()
        val errBody = StopTransactionResponse(
            message = null, status = null, error = "Internal Error"
        )
        api.commitResponse = netServerError(errBody, 500)
        val (manager, _, _) = createManager(api = api)

        val result = manager.commitTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
    }

    @Test
    fun `commitTransaction network error returns Error`() = runTest {
        val api = FakeApiService()
        api.commitResponse = NetworkResponse.NetworkError(IOException("timeout"))
        val (manager, _, _) = createManager(api = api)

        val result = manager.commitTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Network error"))
    }

    @Test
    fun `commitTransaction unknown error returns Error`() = runTest {
        val api = FakeApiService()
        api.commitResponse = NetworkResponse.UnknownError(RuntimeException("unexpected"), null)
        val (manager, _, _) = createManager(api = api)

        val result = manager.commitTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Unknown error"))
    }

    @Test
    fun `stopTransaction unknown error returns Error`() = runTest {
        val api = FakeApiService()
        api.stopResponse = NetworkResponse.UnknownError(RuntimeException("crash"), null)
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("Unknown error"))
    }

    // =====================================================================
    // POLL RESULT
    // =====================================================================

    @Test
    fun `pollResult returns Success when output has SUCCESS status`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
        assertEquals(TransactionStage.COMPLETED.name, dm.uploadStages[SESSION_ID])
    }

    @Test
    fun `pollResult returns Success for PARTIAL_COMPLETED status`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.PARTIAL_COMPLETED))
        )
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
    }

    @Test
    fun `pollResult returns Failed when all outputs have FAILURE status`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.FAILURE)))
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Failed)
        assertEquals(TransactionStage.FAILURE.name, dm.uploadStages[SESSION_ID])
    }

    @Test
    fun `pollResult retries on 202 then succeeds`() = runTest {
        val api = FakeApiService()
        val processing = ScribeResultResponse(data = null)
        api.pollResponses = mutableListOf(
            netSuccess(processing, 202),
            netSuccess(processing, 202),
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
        assertEquals(3, api.pollCallCount)
    }

    @Test
    fun `pollResult returns Timeout after max retries`() = runTest {
        val api = FakeApiService()
        val processing = ScribeResultResponse(data = null)
        api.pollResponses = MutableList(10) { netSuccess(processing, 202) }
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Timeout)
        assertEquals(5, api.pollCallCount) // POLL_MAX_RETRIES = 5
    }

    @Test
    fun `pollResult retries on server error then succeeds`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(
            netServerError(null, 503),
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
    }

    @Test
    fun `pollResult retries on network error`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(
            NetworkResponse.NetworkError(IOException("timeout")),
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
    }

    @Test
    fun `pollResult retries on unknown error then succeeds`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(
            NetworkResponse.UnknownError(RuntimeException("oops"), null),
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
    }

    @Test
    fun `pollResult still-in-progress status continues polling`() = runTest {
        val api = FakeApiService()
        // First response: success but status is neither SUCCESS nor FAILURE (still in progress)
        api.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.IN_PROGRESS)),
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Success)
    }

    // =====================================================================
    // RETRY FAILED UPLOADS
    // =====================================================================

    @Test
    fun `retryFailedUploads succeeds when no failed chunks`() = runTest {
        val dm = FakeDataManager()
        dm.failedChunksList = emptyList()
        dm.allChunksUploaded = true
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
    }

    @Test
    fun `retryFailedUploads retries and marks uploaded on success`() = runTest {
        val chunkFile = tempFolder.newFile("chunk.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c1", "1.m4a", filePath = chunkFile.absolutePath)
        )
        dm.sessionEntity = makeSessionEntity(folderName = "260302", bid = "bid-1")
        dm.allChunksUploaded = true
        val uploader = FakeChunkUploader(UploadResult.Success("s3://ok"))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
        assertTrue(dm.inProgressChunks.contains("c1"))
        assertTrue(dm.uploadedChunkIds.contains("c1"))
    }

    @Test
    fun `retryFailedUploads marks failed on upload failure`() = runTest {
        val chunkFile = tempFolder.newFile("fail_chunk.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c2", "2.m4a", filePath = chunkFile.absolutePath)
        )
        dm.sessionEntity = makeSessionEntity()
        dm.allChunksUploaded = false
        val uploader = FakeChunkUploader(UploadResult.Failure("S3 error", isRetryable = true))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertFalse(result)
        assertTrue(dm.failedChunkIds.contains("c2"))
    }

    @Test
    fun `retryFailedUploads skips missing files`() = runTest {
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c3", "3.m4a", filePath = "/nonexistent/path/3.m4a")
        )
        dm.sessionEntity = makeSessionEntity()
        dm.allChunksUploaded = false
        val uploader = FakeChunkUploader()
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertFalse(result)
        assertEquals(0, uploader.uploadCount)
    }

    @Test
    fun `retryFailedUploads retries chunks that were previously exhausted`() = runTest {
        val chunkFile = tempFolder.newFile("exhausted_chunk.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = emptyList()
        dm.retryExhaustedChunksList = listOf(
            makeChunkEntity("c-exhaust", "1.m4a", filePath = chunkFile.absolutePath)
        )
        dm.sessionEntity = makeSessionEntity(folderName = "260302", bid = "bid-1")
        dm.allChunksUploaded = true
        val uploader = FakeChunkUploader(UploadResult.Success("s3://ok"))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
        assertTrue(dm.inProgressChunks.contains("c-exhaust"))
        assertTrue(dm.uploadedChunkIds.contains("c-exhaust"))
        assertEquals(1, uploader.uploadCount)
    }

    @Test
    fun `retryFailedUploads returns true when previously exhausted chunks are recovered`() =
        runTest {
        val chunkFile1 = tempFolder.newFile("failed.m4a")
        val chunkFile2 = tempFolder.newFile("exhausted.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c-fail", "1.m4a", filePath = chunkFile1.absolutePath)
        )
        dm.retryExhaustedChunksList = listOf(
            makeChunkEntity("c-exhaust2", "2.m4a", filePath = chunkFile2.absolutePath)
        )
        dm.sessionEntity = makeSessionEntity(folderName = "260302", bid = "bid-1")
        dm.allChunksUploaded = true
        val uploader = FakeChunkUploader(UploadResult.Success("s3://ok"))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
        assertTrue(dm.uploadedChunkIds.contains("c-fail"))
        assertTrue(dm.uploadedChunkIds.contains("c-exhaust2"))
        assertEquals(2, uploader.uploadCount)
    }

    // =====================================================================
    // CHECK AND PROGRESS (STATE MACHINE)
    // =====================================================================

    @Test
    fun `checkAndProgress returns error when session not found`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = null
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("not found"))
    }

    @Test
    fun `checkAndProgress INIT stage calls initTransaction`() = runTest {
        val api = FakeApiService()
        api.initResponse = netSuccess(initOk("bid-init"))
        api.stopResponse = netSuccess(stopOk())
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.allChunksUploaded = true
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.INIT.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, sessionConfig = defaultConfig())

        assertTrue(result is TransactionResult.Success)
        assertTrue(api.initCalled)
        assertTrue(api.stopCalled)
        assertTrue(api.commitCalled)
    }

    @Test
    fun `checkAndProgress INIT stage without config tries deserialization`() = runTest {
        val api = FakeApiService()
        api.initResponse = netSuccess(initOk("bid-r"))
        api.stopResponse = netSuccess(stopOk())
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.allChunksUploaded = true
        val metadataJson = """{"input_language":["en-IN"],"mode":"dictation","model_type":"pro"}"""
        dm.sessionEntity = makeSessionEntity(
            uploadStage = TransactionStage.INIT.name,
            sessionMetadata = metadataJson
        )
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, sessionConfig = null)

        assertTrue(result is TransactionResult.Success)
    }

    @Test
    fun `checkAndProgress INIT stage no config no metadata returns error`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(
            uploadStage = TransactionStage.INIT.name,
            sessionMetadata = null
        )
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, sessionConfig = null)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("config"))
    }

    @Test
    fun `checkAndProgress STOP stage retries uploads then calls stop`() = runTest {
        val api = FakeApiService()
        api.stopResponse = netSuccess(stopOk())
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = emptyList()
        dm.allChunksUploaded = true
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        assertTrue(api.stopCalled)
        assertTrue(api.commitCalled)
    }

    @Test
    fun `checkAndProgress STOP stage not all uploaded without force returns error`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = emptyList()
        dm.allChunksUploaded = false
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, force = false)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("forceCommit"))
    }

    @Test
    fun `checkAndProgress STOP stage not all uploaded with force proceeds`() = runTest {
        val api = FakeApiService()
        api.stopResponse = netSuccess(stopOk())
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = emptyList()
        dm.allChunksUploaded = false
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, force = true)

        assertTrue(result is TransactionResult.Success)
        assertTrue(api.stopCalled)
        assertTrue(api.commitCalled)
    }

    @Test
    fun `checkAndProgress COMMIT stage calls commitTransaction`() = runTest {
        val api = FakeApiService()
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.COMMIT.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        assertTrue(api.commitCalled)
        assertEquals(1, api.pollCallCount)
    }

    @Test
    fun `checkAndProgress ANALYZING stage polls and returns success`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(
            netSuccess(makeResultResponse(ResultStatus.SUCCESS))
        )
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.ANALYZING.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
    }

    @Test
    fun `checkAndProgress ANALYZING stage poll timeout returns error`() = runTest {
        val api = FakeApiService()
        val processing = ScribeResultResponse(data = null)
        api.pollResponses = MutableList(10) { netSuccess(processing, 202) }
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.ANALYZING.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("timeout"))
    }

    @Test
    fun `checkAndProgress COMPLETED stage returns Success immediately`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.COMPLETED.name)
        val (manager, _, _) = createManager(dataManager = dm)

        assertTrue(manager.checkAndProgress(SESSION_ID) is TransactionResult.Success)
    }

    @Test
    fun `checkAndProgress FAILURE stage returns Success (terminal)`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.FAILURE.name)
        val (manager, _, _) = createManager(dataManager = dm)

        assertTrue(manager.checkAndProgress(SESSION_ID) is TransactionResult.Success)
    }

    @Test
    fun `checkAndProgress ERROR stage returns Success (terminal)`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.ERROR.name)
        val (manager, _, _) = createManager(dataManager = dm)

        assertTrue(manager.checkAndProgress(SESSION_ID) is TransactionResult.Success)
    }

    // =====================================================================
    // BRANCH COVERAGE IMPROVEMENT
    // =====================================================================

    @Test
    fun `initTransaction with outputTemplates maps correctly`() = runTest {
        val api = FakeApiService()
        api.initResponse = netSuccess(initOk("bid-tpl"))
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val config = SessionConfig(
            languages = listOf("en-IN"),
            mode = "dictation",
            modelType = "pro",
            outputTemplates = listOf(
                OutputTemplate(
                    templateId = "tpl-1",
                    templateType = "soap",
                    templateName = "SOAP Note"
                )
            )
        )

        val result = manager.initTransaction(SESSION_ID, config)

        assertTrue(result is TransactionResult.Success)
        assertTrue(dm.sessionMetadataMap.containsKey(SESSION_ID))
    }

    @Test
    fun `initTransaction with patientDetails maps correctly`() = runTest {
        val api = FakeApiService()
        api.initResponse = netSuccess(initOk("bid-pt"))
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val config = SessionConfig(
            languages = listOf("en-IN"),
            mode = "dictation",
            modelType = "pro",
            patientDetails = PatientDetail(
                age = 45,
                biologicalSex = "male",
                name = "John Doe",
                patientId = "P123",
                visitId = "V456"
            )
        )

        val result = manager.initTransaction(SESSION_ID, config)

        assertTrue(result is TransactionResult.Success)
        assertTrue(dm.sessionMetadataMap.containsKey(SESSION_ID))
        assertTrue(dm.sessionMetadataMap[SESSION_ID]!!.contains("John Doe"))
    }

    @Test
    fun `initTransaction server error with null body message falls back`() = runTest {
        val api = FakeApiService()
        val errBody = InitTransactionResponse(
            bId = null, message = null, status = null, txnId = null, error = null
        )
        api.initResponse = netServerError(errBody, 500)
        val (manager, _, _) = createManager(api = api)

        val result = manager.initTransaction(SESSION_ID, defaultConfig())

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message == "Init failed")
    }

    @Test
    fun `stopTransaction with no uploaded chunks sends empty lists`() = runTest {
        val api = FakeApiService()
        api.stopResponse = netSuccess(stopOk())
        val dm = FakeDataManager()
        dm.uploadedChunksList = emptyList()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Success)
        assertEquals(0, api.lastStopRequest!!.audioFiles.size)
    }

    @Test
    fun `stopTransaction server error with null body falls back`() = runTest {
        val api = FakeApiService()
        val errBody = StopTransactionResponse(message = null, status = null, error = null)
        api.stopResponse = netServerError(errBody, 500)
        val (manager, _, _) = createManager(api = api)

        val result = manager.stopTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message == "Stop failed")
    }

    @Test
    fun `commitTransaction server error with null body falls back`() = runTest {
        val api = FakeApiService()
        val errBody = StopTransactionResponse(message = null, status = null, error = null)
        api.commitResponse = netServerError(errBody, 500)
        val (manager, _, _) = createManager(api = api)

        val result = manager.commitTransaction(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message == "Commit failed")
    }

    @Test
    fun `pollResult with null data returns Timeout after max retries`() = runTest {
        val api = FakeApiService()
        val emptyResult = ScribeResultResponse(data = null)
        api.pollResponses = MutableList(10) { netSuccess(emptyResult) }
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Timeout)
    }

    @Test
    fun `pollResult with null output list returns Timeout`() = runTest {
        val api = FakeApiService()
        val resultWithNullOutput = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = null
            )
        )
        api.pollResponses = MutableList(10) { netSuccess(resultWithNullOutput) }
        val (manager, _, _) = createManager(api = api)

        val result = manager.pollResult(SESSION_ID)

        assertTrue(result is TransactionPollResult.Timeout)
    }

    @Test
    fun `pollResult with empty output list returns Failed (vacuous all-failure)`() = runTest {
        val api = FakeApiService()
        val resultWithEmptyOutput = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = emptyList(),
                templateResults = null
            )
        )
        api.pollResponses = MutableList(10) { netSuccess(resultWithEmptyOutput) }
        val dm = FakeDataManager()
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.pollResult(SESSION_ID)

        // Empty list: any{} = false, all{} = true (vacuously), so returns Failed
        assertTrue(result is TransactionPollResult.Failed)
        assertEquals(TransactionStage.FAILURE.name, dm.uploadStages[SESSION_ID])
    }

    @Test
    fun `retryFailedUploads with null session uses empty folderName and bid`() = runTest {
        val chunkFile = tempFolder.newFile("null_session_chunk.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c-null", "1.m4a", filePath = chunkFile.absolutePath)
        )
        dm.sessionEntity = null
        dm.allChunksUploaded = true
        val uploader = FakeChunkUploader(UploadResult.Success("s3://ok"))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
        assertEquals(1, uploader.uploadCount)
    }

    @Test
    fun `retryFailedUploads with session having null folderName uses empty string`() = runTest {
        val chunkFile = tempFolder.newFile("null_folder_chunk.m4a")
        val dm = FakeDataManager()
        dm.failedChunksList = listOf(
            makeChunkEntity("c-nf", "1.m4a", filePath = chunkFile.absolutePath)
        )
        dm.sessionEntity = makeSessionEntity(folderName = null, bid = null)
        dm.allChunksUploaded = true
        val uploader = FakeChunkUploader(UploadResult.Success("s3://ok"))
        val (manager, _, _) = createManager(dataManager = dm, uploader = uploader)

        val result = manager.retryFailedUploads(SESSION_ID)

        assertTrue(result)
    }

    @Test
    fun `checkAndProgress INIT stage with invalid metadata JSON returns error`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(
            uploadStage = TransactionStage.INIT.name,
            sessionMetadata = "{{invalid json}}"
        )
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, sessionConfig = null)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("config"))
    }

    @Test
    fun `checkAndProgress ANALYZING stage poll failure returns error`() = runTest {
        val api = FakeApiService()
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.FAILURE)))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.ANALYZING.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
    }

    @Test
    fun `checkAndProgress STOP stage retry fails then returns error`() = runTest {
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = listOf(
            makeChunkEntity("c-stuck", "1.m4a", filePath = "/nonexistent/1.m4a")
        )
        dm.allChunksUploaded = false
        val (manager, _, _) = createManager(dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, force = false)

        assertTrue(result is TransactionResult.Error)
        assertTrue((result as TransactionResult.Error).message.contains("forceCommit"))
    }

    @Test
    fun `checkAndProgress STOP stage with force skips upload check`() = runTest {
        val api = FakeApiService()
        api.stopResponse = netSuccess(stopOk())
        api.commitResponse = netSuccess(stopOk())
        api.pollResponses = mutableListOf(netSuccess(makeResultResponse(ResultStatus.SUCCESS)))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = listOf(
            makeChunkEntity("c-force", "1.m4a", filePath = "/nonexistent/1.m4a")
        )
        dm.allChunksUploaded = false
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, force = true)

        assertTrue(result is TransactionResult.Success)
        assertTrue(api.stopCalled)
    }

    @Test
    fun `checkAndProgress INIT stage with init failure returns error`() = runTest {
        val api = FakeApiService()
        api.initResponse = NetworkResponse.NetworkError(IOException("no network"))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.INIT.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID, sessionConfig = defaultConfig())

        assertTrue(result is TransactionResult.Error)
    }

    @Test
    fun `checkAndProgress STOP stage with stop failure returns error`() = runTest {
        val api = FakeApiService()
        api.stopResponse = NetworkResponse.NetworkError(IOException("no network"))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.STOP.name)
        dm.failedChunksList = emptyList()
        dm.allChunksUploaded = true
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
    }

    @Test
    fun `checkAndProgress COMMIT stage with commit failure returns error`() = runTest {
        val api = FakeApiService()
        api.commitResponse = NetworkResponse.NetworkError(IOException("no network"))
        val dm = FakeDataManager()
        dm.sessionEntity = makeSessionEntity(uploadStage = TransactionStage.COMMIT.name)
        val (manager, _, _) = createManager(api = api, dataManager = dm)

        val result = manager.checkAndProgress(SESSION_ID)

        assertTrue(result is TransactionResult.Error)
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun makeChunkEntity(
        chunkId: String,
        fileName: String,
        startTimeMs: Long = 0,
        endTimeMs: Long = 10000,
        filePath: String = "/tmp/$fileName"
    ) = AudioChunkEntity(
        chunkId = chunkId,
        sessionId = SESSION_ID,
        chunkIndex = 0,
        filePath = filePath,
        fileName = fileName,
        startTimeMs = startTimeMs,
        endTimeMs = endTimeMs,
        durationMs = endTimeMs - startTimeMs,
        createdAt = System.currentTimeMillis()
    )

    private fun makeSessionEntity(
        uploadStage: String = TransactionStage.INIT.name,
        folderName: String? = "260302",
        bid: String? = "bid-1",
        sessionMetadata: String? = null
    ) = SessionEntity(
        sessionId = SESSION_ID,
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        state = "RECORDING",
        folderName = folderName,
        bid = bid,
        uploadStage = uploadStage,
        sessionMetadata = sessionMetadata
    )

    private fun makeResultResponse(status: ResultStatus): ScribeResultResponse {
        val output = ScribeResultResponse.Data.Output(
            errors = null,
            name = "output",
            status = status,
            templateId = null,
            type = null,
            value = "test result",
            warnings = null
        )
        return ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = listOf(output),
                templateResults = null
            )
        )
    }

    // =====================================================================
    // FAKES
    // =====================================================================

    internal class FakeApiService : ScribeApiService {
        var initResponse: NetworkResponse<InitTransactionResponse, InitTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var stopResponse: NetworkResponse<StopTransactionResponse, StopTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var commitResponse: NetworkResponse<StopTransactionResponse, StopTransactionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var pollResponses: MutableList<NetworkResponse<ScribeResultResponse, ScribeResultResponse>> =
            mutableListOf()

        var initCalled = false
        var stopCalled = false
        var commitCalled = false
        var pollCallCount = 0
        var lastStopRequest: StopTransactionRequest? = null

        override suspend fun initTransaction(
            sessionId: String,
            request: InitTransactionRequest
        ): NetworkResponse<InitTransactionResponse, InitTransactionResponse> {
            initCalled = true
            return initResponse
        }

        override suspend fun stopTransaction(
            sessionId: String,
            request: StopTransactionRequest
        ): NetworkResponse<StopTransactionResponse, StopTransactionResponse> {
            stopCalled = true
            lastStopRequest = request
            return stopResponse
        }

        override suspend fun commitTransaction(
            sessionId: String,
            request: StopTransactionRequest
        ): NetworkResponse<StopTransactionResponse, StopTransactionResponse> {
            commitCalled = true
            return commitResponse
        }

        override suspend fun getTransactionResult(
            sessionId: String
        ): NetworkResponse<ScribeResultResponse, ScribeResultResponse> {
            val idx = pollCallCount.coerceAtMost(pollResponses.size - 1)
            pollCallCount++
            return pollResponses[idx]
        }

        // Unused endpoints
        override suspend fun getS3Config(url: String) = throw NotImplementedError()
        override suspend fun convertTransactionResult(sessionId: String, templateId: String) =
            throw NotImplementedError()

        override suspend fun updateSessionOutput(sessionId: String, request: UpdateSessionRequest) =
            throw NotImplementedError()

        override suspend fun getTemplates() = throw NotImplementedError()
        override suspend fun updateTemplates(requestBody: UpdateTemplatesRequest) =
            throw NotImplementedError()

        override suspend fun getUserConfig() = throw NotImplementedError()
        override suspend fun updateUserConfig(request: UpdateUserConfigRequest) =
            throw NotImplementedError()

        override suspend fun getHistory(queries: Map<String, String>) = throw NotImplementedError()
    }

    internal inner class FakeDataManager : DataManager {
        val uploadStages = mutableMapOf<String, String>()
        val sessionMetadataMap = mutableMapOf<String, String>()
        val inProgressChunks = mutableSetOf<String>()
        val uploadedChunkIds = mutableSetOf<String>()
        val failedChunkIds = mutableSetOf<String>()
        var folderBidUpdated = false
        var sessionEntity: SessionEntity? = null
        var uploadedChunksList: List<AudioChunkEntity> = emptyList()
        var failedChunksList: List<AudioChunkEntity> = emptyList()
        var retryExhaustedChunksList: List<AudioChunkEntity> = emptyList()
        val resetRetryCountIds = mutableSetOf<String>()
        var allChunksUploaded = false

        override suspend fun updateUploadStage(sessionId: String, stage: String) {
            uploadStages[sessionId] = stage
            // Automatically update the session entity so that checkAndProgress loop progresses
            sessionEntity = sessionEntity?.copy(uploadStage = stage)
                ?: makeSessionEntity(uploadStage = stage)
        }

        override suspend fun updateFolderAndBid(
            sessionId: String,
            folderName: String,
            bid: String
        ) {
            folderBidUpdated = true
        }

        override suspend fun updateSessionMetadata(sessionId: String, metadata: String) {
            sessionMetadataMap[sessionId] = metadata
        }

        override suspend fun getSession(sessionId: String) = sessionEntity
        override suspend fun getUploadedChunks(sessionId: String) = uploadedChunksList
        override suspend fun getFailedChunks(sessionId: String, maxRetries: Int) = failedChunksList
        override suspend fun areAllChunksUploaded(sessionId: String) = allChunksUploaded
        override suspend fun getRetryExhaustedChunks(sessionId: String, maxRetries: Int) =
            retryExhaustedChunksList

        override suspend fun resetRetryCount(chunkId: String) {
            resetRetryCountIds.add(chunkId)
        }
        override suspend fun markInProgress(chunkId: String) {
            inProgressChunks.add(chunkId)
        }

        override suspend fun markUploaded(chunkId: String) {
            uploadedChunkIds.add(chunkId)
        }

        override suspend fun markFailed(chunkId: String) {
            failedChunkIds.add(chunkId)
        }

        // Unused stubs
        override suspend fun saveSession(session: SessionEntity) {}
        override suspend fun saveChunk(chunk: AudioChunkEntity) {}
        override suspend fun updateSessionState(sessionId: String, state: String) {}
        override suspend fun updateChunkCount(sessionId: String, count: Int) {}
        override suspend fun getPendingChunks(sessionId: String) = emptyList<AudioChunkEntity>()
        override suspend fun getChunkCount(sessionId: String) = 0
        override fun sessionFlow(sessionId: String): Flow<SessionEntity?> = emptyFlow()
        override suspend fun deleteSession(sessionId: String) {}
        override suspend fun getSessionsByStage(stage: String) = emptyList<SessionEntity>()
        override suspend fun getAllChunks(sessionId: String) =
            uploadedChunksList + failedChunksList + retryExhaustedChunksList
        override suspend fun getAllSessions() = emptyList<SessionEntity>()
    }

    internal class FakeChunkUploader(
        private val result: UploadResult = UploadResult.Success("s3://test/ok.m4a")
    ) : ChunkUploader {
        var uploadCount = 0

        override suspend fun upload(file: File, metadata: UploadMetadata): UploadResult {
            uploadCount++
            return result
        }
    }

    internal class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
