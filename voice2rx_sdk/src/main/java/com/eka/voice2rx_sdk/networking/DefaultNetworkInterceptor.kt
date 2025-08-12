package com.eka.voice2rx_sdk.networking

import okhttp3.Interceptor
import okhttp3.Response


class DefaultNetworkInterceptor(private val okHttpSetup: IOkHttpSetup) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url.toString()
        val newRequest = request.newBuilder()
        val defaultHeaders = okHttpSetup.getDefaultHeaders(url)
        defaultHeaders.forEach {
            newRequest.addHeader(it.key, it.value)
        }
        return chain.proceed(newRequest.build())
    }
}