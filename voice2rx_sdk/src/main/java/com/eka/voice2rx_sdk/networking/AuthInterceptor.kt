package com.eka.voice2rx_sdk.networking

import android.os.Handler
import android.os.Looper
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

class AuthInterceptor(private val okHttpSetup: IOkHttpSetup) : Interceptor {
    private var pendingRequests: MutableList<(Request) -> Response> = mutableListOf()
    private var isRefreshing = false

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        synchronized(this) {
            if (isRefreshing) {
                return handlePendingRequest(chain = chain, request = originalRequest)
            }

            val initialResponse = chain.proceed(originalRequest)

            if (initialResponse.code == 401) {
                isRefreshing = true
                val url = originalRequest.url.toString()
                val newRequest = originalRequest.newBuilder().signedRequest(url)

                if (newRequest != null) {
                    // Complete pending requests with the new token
                    pendingRequests.forEach { it(newRequest.build()) }
                    pendingRequests.clear()
                    isRefreshing = false
                    return chain.proceed(newRequest.build())
                } else {
                    isRefreshing = false
                    return initialResponse
                }
            } else {
                return initialResponse
            }
        }
    }

    private fun handlePendingRequest(chain: Interceptor.Chain, request: Request): Response {
        val response: Response? = null
        val callback = { r: Request -> chain.proceed(r) }

        pendingRequests.add(callback)
        return response ?: chain.proceed(request)
    }

    private fun Request.Builder.signedRequest(url: String): Request.Builder? {
        val headers = okHttpSetup.refreshAuthToken(url)
        if (headers != null) {
            headers.forEach {
                header(it.key, it.value)
            }
            return this
        }

        Handler(Looper.getMainLooper()).post {
            okHttpSetup.onSessionExpire()
        }

        return null
    }
}