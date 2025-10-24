package com.eka.voice2rx_sdk.data.remote.services

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming

interface ModelDownloadApi {

    @Streaming
    @GET("squim_objective.ptl") // Your model path on CDN
    suspend fun downloadModel(
        @Header("If-None-Match") etag: String? = null
    ): Response<ResponseBody>
}