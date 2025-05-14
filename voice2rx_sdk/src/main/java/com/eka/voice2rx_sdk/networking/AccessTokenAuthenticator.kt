package com.eka.voice2rx_sdk.networking

import android.os.Handler
import android.os.Looper
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

class AccessTokenAuthenticator(private val okHttpSetup: IOkHttpSetup) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val request = response.request
        if (request.url.encodedPath.contains("auth/refresh"))
            return null

        return if (request.authTag() == AuthorizationType.ACCESS_TOKEN) {
            val url = request.url.toString()
            val newRequest = request.newBuilder().signedRequest(url)
            return newRequest?.build()
        } else null
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