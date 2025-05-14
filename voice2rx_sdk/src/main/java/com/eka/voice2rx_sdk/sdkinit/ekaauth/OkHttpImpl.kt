package com.eka.voice2rx_sdk.sdkinit.ekaauth

import com.eka.voice2rx_sdk.BuildConfig
import com.eka.voice2rx_sdk.networking.IOkHttpSetup
import kotlinx.coroutines.runBlocking

class OkHttpImpl(
    val ekaAuthConfig: EkaAuthConfig?,
    val defaultHeaders: Map<String, String> = HashMap(),
    var authorizationToken: String,
) : IOkHttpSetup {
    override fun getDefaultHeaders(url: String): Map<String, String> {
        val headers = HashMap<String, String>()
        headers.putAll(defaultHeaders)
        headers["Authorization"] = "Bearer $authorizationToken"
        headers["flavour"] =
            "android_${BuildConfig.SDK_VERSION_NAME}_${BuildConfig.SDK_BUILD_NUMBER}"
        return headers
    }

    override fun onSessionExpire() {
        ekaAuthConfig?.sessionExpired()
    }

    override fun refreshAuthToken(url: String): Map<String, String>? {
        return runBlocking {
            val sessionToken = ekaAuthConfig?.refreshToken()
            authorizationToken = sessionToken ?: authorizationToken
            if (sessionToken.isNullOrBlank()) {
                null
            } else {
                getDefaultHeaders(url)
            }
        }
    }
}