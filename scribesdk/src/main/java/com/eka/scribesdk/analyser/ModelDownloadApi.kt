package com.eka.scribesdk.analyser

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Streaming

internal interface ModelDownloadApi {

    @Streaming
    @GET("squim_objective_for_android.onnx")
    suspend fun downloadModel(
        @Header("If-None-Match") etag: String? = null
    ): Response<ResponseBody>
}
