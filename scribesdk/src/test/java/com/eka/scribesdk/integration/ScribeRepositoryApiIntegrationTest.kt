package com.eka.scribesdk.integration

import com.eka.scribesdk.api.models.ConfigOutputTemplate
import com.eka.scribesdk.api.models.ConsultationMode
import com.eka.scribesdk.api.models.ModelType
import com.eka.scribesdk.api.models.SelectedUserPreferences
import com.eka.scribesdk.api.models.SessionData
import com.eka.scribesdk.api.models.SupportedLanguage
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.data.ScribeRepository
import com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest
import com.eka.scribesdk.data.remote.models.responses.GetConfigResponse
import com.eka.scribesdk.data.remote.models.responses.HistoryItem
import com.eka.scribesdk.data.remote.models.responses.HistoryResponse
import com.eka.scribesdk.data.remote.models.responses.PatientDetailsInfo
import com.eka.scribesdk.data.remote.models.responses.TemplateConversionError
import com.eka.scribesdk.data.remote.models.responses.TemplateConversionResponse
import com.eka.scribesdk.data.remote.models.responses.TemplatesResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateSessionError
import com.eka.scribesdk.data.remote.models.responses.UpdateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateTemplateResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateUserConfigResponse
import com.eka.scribesdk.data.remote.services.ScribeApiService
import com.haroldadmin.cnradapter.NetworkResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.io.IOException

/**
 * Wires [ScribeRepository] to a fake [ScribeApiService] for the APIs that sit
 * outside the recording session: convert, update output, templates, user config, history.
 */
internal class ScribeRepositoryApiIntegrationTest {

    private val api = FakeNewApiService()
    private val repository = ScribeRepository(api, NoOpLogger())

    @Test
    fun `convert returns success and forwards session and template ids`() = runTest {
        api.convertResponse = netSuccess(
            TemplateConversionResponse(
                bId = "bid-1",
                message = "ok",
                status = "success",
                templateId = "tpl-9",
                txnId = "txn-1"
            )
        )

        val result = repository.convertTransactionResult("sess-1", "tpl-9")

        assertTrue(result.isSuccess)
        assertEquals(true, result.getOrNull())
        assertEquals("sess-1", api.lastConvertSessionId)
        assertEquals("tpl-9", api.lastConvertTemplateId)
    }

    @Test
    fun `convert fails when status is not success`() = runTest {
        api.convertResponse = netSuccess(
            TemplateConversionResponse(
                bId = null,
                message = "nope",
                status = "failed",
                templateId = "tpl-9",
                txnId = null
            )
        )

        val result = repository.convertTransactionResult("sess-1", "tpl-9")

        assertTrue(result.isFailure)
        assertEquals("Something went wrong", result.exceptionOrNull()?.message)
    }

    @Test
    fun `convert server error uses display message`() = runTest {
        api.convertResponse = netServerError(
            TemplateConversionResponse(
                bId = null,
                message = null,
                status = null,
                templateId = null,
                txnId = null,
                error = TemplateConversionError(
                    code = "bad_template",
                    displayMessage = "Template not found",
                    message = "missing"
                )
            ),
            400
        )

        val result = repository.convertTransactionResult("sess-1", "missing")

        assertEquals("Template not found", result.exceptionOrNull()?.message)
    }

    @Test
    fun `convert network error returns the thrown failure`() = runTest {
        api.convertResponse = NetworkResponse.NetworkError(IOException("offline"))

        val result = repository.convertTransactionResult("sess-1", "tpl-9")

        assertEquals("offline", result.exceptionOrNull()?.message)
    }

    @Test
    fun `update session output sends document id and data`() = runTest {
        api.updateSessionResponse = netSuccess(
            UpdateSessionResponse(bId = "bid", message = "ok", status = "success", txnId = "txn")
        )

        val result = repository.updateSessionResult(
            "sess-2",
            listOf(SessionData(templateId = "tpl", documentId = "doc-1", data = "note"))
        )

        assertEquals(true, result.getOrNull())
        assertEquals("sess-2", api.lastUpdateSessionId)
        val sent = api.lastUpdateSessionRequest!!
        assertEquals(1, sent.size)
        assertEquals("doc-1", sent[0].documentId)
        assertEquals("note", sent[0].data)
    }

    @Test
    fun `update session output fails when status is not success`() = runTest {
        api.updateSessionResponse = netSuccess(
            UpdateSessionResponse(bId = null, message = "nope", status = "failed", txnId = null)
        )

        val result = repository.updateSessionResult("sess-2", emptyList())

        assertEquals("Something went wrong", result.exceptionOrNull()?.message)
    }

    @Test
    fun `update session output server error uses display message`() = runTest {
        api.updateSessionResponse = netServerError(
            UpdateSessionResponse(
                bId = null,
                message = null,
                status = null,
                txnId = null,
                error = UpdateSessionError(code = "x", displayMessage = "Cannot save", message = "x")
            ),
            500
        )

        val result = repository.updateSessionResult("sess-2", emptyList())

        assertEquals("Cannot save", result.exceptionOrNull()?.message)
    }

    @Test
    fun `get templates maps items and drops entries without an id`() = runTest {
        api.templatesResponse = netSuccess(
            TemplatesResponse(
                items = listOf(
                    TemplatesResponse.Item(
                        default = true,
                        desc = "SOAP",
                        id = "tpl-1",
                        isFavorite = true,
                        sectionIds = listOf("s1", null),
                        title = "SOAP"
                    ),
                    TemplatesResponse.Item(
                        default = false,
                        desc = "skip",
                        id = null,
                        isFavorite = false,
                        sectionIds = emptyList(),
                        title = "No id"
                    ),
                    null
                )
            )
        )

        val result = repository.getTemplates()

        val items = result.getOrThrow()
        assertEquals(1, items.size)
        assertEquals("tpl-1", items[0].id)
        assertEquals("SOAP", items[0].title)
        assertEquals(listOf("s1"), items[0].sectionIds)
        assertTrue(items[0].isFavorite)
    }

    @Test
    fun `get templates returns empty list when items are null`() = runTest {
        api.templatesResponse = netSuccess(TemplatesResponse(items = null))

        assertTrue(repository.getTemplates().getOrThrow().isEmpty())
    }

    @Test
    fun `get templates server error fails`() = runTest {
        api.templatesResponse = netServerError(TemplatesResponse(items = null), 500)

        assertEquals("Error fetching templates", repository.getTemplates().exceptionOrNull()?.message)
    }

    @Test
    fun `update templates sends favourite ids`() = runTest {
        api.updateTemplatesResponse = netSuccess(UpdateTemplateResponse(message = "ok"))

        val result = repository.updateTemplates(listOf("tpl-1", "tpl-2"))

        assertTrue(result.isSuccess)
        val sent = api.lastUpdateTemplatesRequest!!
        assertEquals(listOf("tpl-1", "tpl-2"), sent.data?.myTemplates)
        assertEquals("user", sent.requestType)
    }

    @Test
    fun `update templates server error fails`() = runTest {
        api.updateTemplatesResponse = netServerError(UpdateTemplateResponse(message = "no"), 500)

        assertEquals(
            "Error updating templates",
            repository.updateTemplates(emptyList()).exceptionOrNull()?.message
        )
    }

    @Test
    fun `get user configs maps selected preferences`() = runTest {
        api.userConfigResponse = netSuccess(GetConfigResponse(data = sampleConfigData()))

        val configs = repository.getUserConfigs().getOrThrow()

        assertEquals("ipd", configs.selectedUserPreferences.consultationMode?.id)
        assertEquals("en", configs.selectedUserPreferences.languages.single().id)
        assertEquals("t1", configs.selectedUserPreferences.outputTemplates.single().id)
        assertEquals("pro", configs.selectedUserPreferences.modelType?.id)
        assertEquals("opd", configs.consultationModes.modes.first().id)
    }

    @Test
    fun `get user configs fails when data is null`() = runTest {
        api.userConfigResponse = netSuccess(GetConfigResponse(data = null))

        assertEquals("Error fetching config", repository.getUserConfigs().exceptionOrNull()?.message)
    }

    @Test
    fun `update user configs builds the config request`() = runTest {
        api.updateUserConfigResponse = netSuccess(
            UpdateUserConfigResponse(message = "ok", result = null)
        )
        val preferences = SelectedUserPreferences(
            consultationMode = ConsultationMode(id = "opd", name = "OPD", desc = "outpatient"),
            languages = listOf(
                SupportedLanguage(id = "en", name = "English"),
                SupportedLanguage(id = "hi", name = "Hindi")
            ),
            outputTemplates = listOf(ConfigOutputTemplate(id = "t1", name = "SOAP")),
            modelType = ModelType(id = "pro", name = "Pro", desc = "accurate")
        )

        val result = repository.updateUserConfigs(preferences)

        assertEquals(true, result.getOrNull())
        val data = api.lastUpdateUserConfigRequest!!.data!!
        assertEquals("opd", data.consultationMode)
        assertEquals("pro", data.modelType)
        assertEquals("user", api.lastUpdateUserConfigRequest!!.requestType)
        assertEquals(listOf("en", "hi"), data.inputLanguages?.map { it?.id })
        assertEquals(listOf("English", "Hindi"), data.inputLanguages?.map { it?.name })
        assertEquals("t1", data.outputFormatTemplate?.single()?.id)
        assertEquals("SOAP", data.outputFormatTemplate?.single()?.name)
        assertEquals("custom", data.outputFormatTemplate?.single()?.templateType)
    }

    @Test
    fun `update user configs server error fails`() = runTest {
        api.updateUserConfigResponse = netServerError(
            UpdateUserConfigResponse(message = "no", result = null),
            500
        )

        val result = repository.updateUserConfigs(
            SelectedUserPreferences(consultationMode = null)
        )

        assertEquals("Error updating config", result.exceptionOrNull()?.message)
    }

    @Test
    fun `get history maps items and sends the count query`() = runTest {
        api.historyResponse = netSuccess(
            HistoryResponse(
                data = listOf(
                    HistoryItem(
                        bId = "bid-1",
                        createdAt = "2025-01-01",
                        flavour = "lite",
                        mode = "opd",
                        oid = "oid-1",
                        processingStatus = "completed",
                        txnId = "txn-1",
                        userStatus = "active",
                        uuid = "uuid-1",
                        version = "v2",
                        patientDetails = PatientDetailsInfo(
                            age = "30",
                            biologicalSex = "male",
                            name = "John Doe",
                            patientId = "p-1",
                            visitId = "v-1"
                        )
                    )
                )
            )
        )

        val items = repository.getHistory(25)

        assertEquals(mapOf("count" to "25"), api.lastHistoryQueries)
        assertEquals(1, items.size)
        assertEquals("txn-1", items[0].txnId)
        assertEquals("John Doe", items[0].patientDetails?.name)
        assertEquals(30, items[0].patientDetails?.age)
        assertEquals("p-1", items[0].patientDetails?.patientId)
    }

    @Test
    fun `get history without count sends an empty query`() = runTest {
        api.historyResponse = netSuccess(HistoryResponse(data = emptyList()))

        assertTrue(repository.getHistory().isEmpty())
        assertTrue(api.lastHistoryQueries!!.isEmpty())
    }

    @Test
    fun `get history returns an empty list on server and network errors`() = runTest {
        api.historyResponse = netServerError(HistoryResponse(data = null), 500)
        assertTrue(repository.getHistory(10).isEmpty())

        api.historyResponse = NetworkResponse.NetworkError(IOException("offline"))
        assertTrue(repository.getHistory(10).isEmpty())
    }

    private fun sampleConfigData(): GetConfigResponse.Data {
        return GetConfigResponse.Data(
            consultationModes = listOf(
                GetConfigResponse.Data.ConsultationModeItem(id = "opd", name = "OPD", desc = "out"),
                GetConfigResponse.Data.ConsultationModeItem(id = "ipd", name = "IPD", desc = "in")
            ),
            maxSelection = GetConfigResponse.Data.MaxSelection(
                consultationModes = 1,
                supportedLanguages = 2,
                supportedOutputFormats = 1
            ),
            myTemplates = listOf(GetConfigResponse.Data.MyTemplate(id = "t1", name = "SOAP")),
            selectedPreferences = GetConfigResponse.Data.SelectedPreferences(
                autoDownload = null,
                consultationModeId = "ipd",
                languages = listOf(GetConfigResponse.Data.SupportedLanguageItem(id = "en", name = "English")),
                modelType = "pro",
                outputFormats = listOf(GetConfigResponse.Data.MyTemplate(id = "t1", name = "SOAP"))
            ),
            settings = null,
            supportedLanguages = listOf(
                GetConfigResponse.Data.SupportedLanguageItem(id = "en", name = "English")
            ),
            supportedOutputFormats = null
        )
    }

    private fun <T> okResponse(body: T, code: Int): Response<T> {
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

    private class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    private class FakeNewApiService : ScribeApiService {
        var convertResponse: NetworkResponse<TemplateConversionResponse, TemplateConversionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var updateSessionResponse: NetworkResponse<UpdateSessionResponse, UpdateSessionResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var templatesResponse: NetworkResponse<TemplatesResponse, TemplatesResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var updateTemplatesResponse: NetworkResponse<UpdateTemplateResponse, UpdateTemplateResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var userConfigResponse: NetworkResponse<GetConfigResponse, GetConfigResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var updateUserConfigResponse: NetworkResponse<UpdateUserConfigResponse, UpdateUserConfigResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))
        var historyResponse: NetworkResponse<HistoryResponse, HistoryResponse> =
            NetworkResponse.NetworkError(IOException("Not configured"))

        var lastConvertSessionId: String? = null
        var lastConvertTemplateId: String? = null
        var lastUpdateSessionId: String? = null
        var lastUpdateSessionRequest: UpdateSessionRequest? = null
        var lastUpdateTemplatesRequest: UpdateTemplatesRequest? = null
        var lastUpdateUserConfigRequest: UpdateUserConfigRequest? = null
        var lastHistoryQueries: Map<String, String>? = null

        override suspend fun convertTransactionResult(
            sessionId: String,
            templateId: String
        ): NetworkResponse<TemplateConversionResponse, TemplateConversionResponse> {
            lastConvertSessionId = sessionId
            lastConvertTemplateId = templateId
            return convertResponse
        }

        override suspend fun updateSessionOutput(
            sessionId: String,
            request: UpdateSessionRequest
        ): NetworkResponse<UpdateSessionResponse, UpdateSessionResponse> {
            lastUpdateSessionId = sessionId
            lastUpdateSessionRequest = request
            return updateSessionResponse
        }

        override suspend fun getTemplates(): NetworkResponse<TemplatesResponse, TemplatesResponse> =
            templatesResponse

        override suspend fun updateTemplates(
            requestBody: UpdateTemplatesRequest
        ): NetworkResponse<UpdateTemplateResponse, UpdateTemplateResponse> {
            lastUpdateTemplatesRequest = requestBody
            return updateTemplatesResponse
        }

        override suspend fun getUserConfig(): NetworkResponse<GetConfigResponse, GetConfigResponse> =
            userConfigResponse

        override suspend fun updateUserConfig(
            request: UpdateUserConfigRequest
        ): NetworkResponse<UpdateUserConfigResponse, UpdateUserConfigResponse> {
            lastUpdateUserConfigRequest = request
            return updateUserConfigResponse
        }

        override suspend fun getHistory(
            queries: Map<String, String>
        ): NetworkResponse<HistoryResponse, HistoryResponse> {
            lastHistoryQueries = queries
            return historyResponse
        }

        override suspend fun getS3Config(url: String) = throw NotImplementedError()
        override suspend fun initTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
        ) = throw NotImplementedError()

        override suspend fun stopTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
        ) = throw NotImplementedError()

        override suspend fun commitTransaction(
            sessionId: String,
            request: com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
        ) = throw NotImplementedError()

        override suspend fun getTransactionResult(sessionId: String, templateId: String?) =
            throw NotImplementedError()
    }
}
