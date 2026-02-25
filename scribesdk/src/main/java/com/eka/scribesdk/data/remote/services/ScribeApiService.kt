package com.eka.scribesdk.data.remote.services

import com.eka.scribesdk.data.remote.models.AwsS3ConfigResponse
import com.eka.scribesdk.data.remote.models.requests.InitTransactionRequest
import com.eka.scribesdk.data.remote.models.requests.StopTransactionRequest
import com.eka.scribesdk.data.remote.models.responses.InitTransactionResponse
import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import com.eka.scribesdk.data.remote.models.responses.StopTransactionResponse
import com.haroldadmin.cnradapter.NetworkResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
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
}
