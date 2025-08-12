package com.eka.voice2rx_sdk.data.remote.services

import com.eka.voice2rx.data.remote.models.AwsS3ConfigResponse
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxInitTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.requests.Voice2RxStopTransactionRequest
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxInitTransactionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxStopTransactionResponse
import com.eka.voice2rx_sdk.data.remote.models.responses.Voice2RxTransactionResult
import com.haroldadmin.cnradapter.NetworkResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

interface Voice2RxService {
    @GET
    suspend fun getS3Config(
        @Url url: String,
    ): NetworkResponse<AwsS3ConfigResponse, AwsS3ConfigResponse>

    @GET("voice/api/v2/status/{session_id}")
    suspend fun getVoice2RxTransactionResult(
        @Path("session_id") sessionId: String,
    ): NetworkResponse<Voice2RxTransactionResult, Voice2RxTransactionResult>

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
}