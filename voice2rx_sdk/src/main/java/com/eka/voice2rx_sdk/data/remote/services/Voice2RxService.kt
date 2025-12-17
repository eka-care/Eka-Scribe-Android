package com.eka.voice2rx_sdk.data.remote.services

import com.eka.voice2rx.data.remote.models.AwsS3ConfigResponse
import com.eka.voice2rx_sdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxInitTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxStopTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeResult
import com.eka.voice2rx_sdk.data.remote.models.responses.EkaScribeResultV3
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplateConversionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.TemplatesResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.UpdateSessionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.UpdateTemplateResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxHistoryResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxInitTransactionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStopTransactionResponse
import com.haroldadmin.cnradapter.NetworkResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.QueryMap
import retrofit2.http.Url

interface Voice2RxService {
    @GET
    suspend fun getS3Config(
        @Url url: String,
    ): NetworkResponse<AwsS3ConfigResponse, AwsS3ConfigResponse>
    @GET("voice/api/v3/status/{session_id}")
    suspend fun getVoice2RxTransactionResultV3(
        @Path("session_id") sessionId: String,
    ): NetworkResponse<EkaScribeResult, EkaScribeResult>

    @GET("voice/api/v3/status/{session_id}")
    suspend fun getVoice2RxTransactionResult(
        @Path("session_id") sessionId: String,
    ): NetworkResponse<EkaScribeResultV3, EkaScribeResultV3>

    @POST("voice/api/v2/transaction/init/{session_id}")
    suspend fun initTransaction(
        @Path("session_id") sessionId: String,
        @Body request: Voice2RxInitTransactionRequest
    ): NetworkResponse<Voice2RxInitTransactionResponse, Voice2RxInitTransactionResponse>

    @POST("voice/api/v2/transaction/stop/{session_id}")
    suspend fun stopTransaction(
        @Path("session_id") sessionId: String,
        @Body request: Voice2RxStopTransactionRequest
    ): NetworkResponse<Voice2RxStopTransactionResponse, Voice2RxStopTransactionResponse>

    @POST("voice/api/v2/transaction/commit/{session_id}")
    suspend fun commitTransaction(
        @Path("session_id") sessionId: String,
        @Body request: Voice2RxStopTransactionRequest
    ): NetworkResponse<Voice2RxStopTransactionResponse, Voice2RxStopTransactionResponse>

    @POST("voice/api/v1/transaction/{session_id}/convert-to-template/{template_id}")
    suspend fun convertTransactionResult(
        @Path("session_id") sessionId: String,
        @Path("template_id") templateId: String,
    ): NetworkResponse<TemplateConversionResponse, TemplateConversionResponse>

    @PATCH("voice/api/v3/status/{session_id}")
    suspend fun updateSessionOutput(
        @Path("session_id") sessionId: String,
        @Body request: UpdateSessionRequest
    ): NetworkResponse<UpdateSessionResponse, UpdateSessionResponse>

    @GET("voice/api/v1/template")
    suspend fun getTemplates(): NetworkResponse<TemplatesResponse, TemplatesResponse>

    @PUT("voice/api/v2/config")
    suspend fun updateTemplates(
        @Body requestBody: UpdateTemplatesRequest
    ): NetworkResponse<UpdateTemplateResponse, UpdateTemplateResponse>

    @GET("voice/api/v2/transaction/history")
    suspend fun getHistory(
        @QueryMap queries: Map<String, String>
    ): NetworkResponse<Voice2RxHistoryResponse, Voice2RxHistoryResponse>
}