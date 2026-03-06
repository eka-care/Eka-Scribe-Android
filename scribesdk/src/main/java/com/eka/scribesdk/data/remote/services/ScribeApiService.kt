package com.eka.scribesdk.data.remote.services

import com.eka.scribesdk.data.remote.models.AwsS3ConfigResponse
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateSessionRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateTemplatesRequest
import com.eka.scribesdk.data.remote.models.requests.UpdateUserConfigRequest
import com.eka.scribesdk.data.remote.models.responses.GetConfigResponse
import com.eka.scribesdk.data.remote.models.responses.HistoryResponse
import com.eka.scribesdk.data.remote.models.responses.InitTransactionResponse
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.StopTransactionResponse
import com.eka.scribesdk.data.remote.models.responses.TemplateConversionResponse
import com.eka.scribesdk.data.remote.models.responses.TemplatesResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateSessionResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateTemplateResponse
import com.eka.scribesdk.data.remote.models.responses.UpdateUserConfigResponse
import com.haroldadmin.cnradapter.NetworkResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.QueryMap
import retrofit2.http.Url

internal interface ScribeApiService {
    @GET
    suspend fun getS3Config(
        @Url url: String
    ): NetworkResponse<AwsS3ConfigResponse, AwsS3ConfigResponse>

    @POST("voice/api/v2/transaction/init/{session_id}")
    suspend fun initTransaction(
        @Path("session_id") sessionId: String,
        @Body request: InitTransactionRequest
    ): NetworkResponse<InitTransactionResponse, InitTransactionResponse>

    @POST("voice/api/v2/transaction/stop/{session_id}")
    suspend fun stopTransaction(
        @Path("session_id") sessionId: String,
        @Body request: StopTransactionRequest
    ): NetworkResponse<StopTransactionResponse, StopTransactionResponse>

    @POST("voice/api/v2/transaction/commit/{session_id}")
    suspend fun commitTransaction(
        @Path("session_id") sessionId: String,
        @Body request: StopTransactionRequest
    ): NetworkResponse<StopTransactionResponse, StopTransactionResponse>

    @GET("voice/api/v3/status/{session_id}")
    suspend fun getTransactionResult(
        @Path("session_id") sessionId: String
    ): NetworkResponse<ScribeResultResponse, ScribeResultResponse>

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

    @GET("voice/api/v2/config")
    suspend fun getUserConfig(): NetworkResponse<GetConfigResponse, GetConfigResponse>

    @PUT("voice/api/v2/config")
    suspend fun updateUserConfig(
        @Body request: UpdateUserConfigRequest
    ): NetworkResponse<UpdateUserConfigResponse, UpdateUserConfigResponse>

    @GET("voice/api/v2/transaction/history")
    suspend fun getHistory(
        @QueryMap queries: Map<String, String>
    ): NetworkResponse<HistoryResponse, HistoryResponse>
}
