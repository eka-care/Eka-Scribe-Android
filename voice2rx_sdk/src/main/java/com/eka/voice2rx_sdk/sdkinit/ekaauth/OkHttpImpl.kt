package com.eka.voice2rx_sdk.sdkinit.ekaauth

import com.eka.voice2rx_sdk.BuildConfig
import com.eka.voice2rx_sdk.networking.IOkHttpSetup
import kotlinx.coroutines.runBlocking

class OkHttpImpl(
    val ekaAuthConfig: EkaAuthConfig,
    val defaultHeaders: Map<String, String> = HashMap()
) : IOkHttpSetup {
    override fun getDefaultHeaders(url: String): Map<String, String> {
        val headers = HashMap<String, String>()
        headers.putAll(defaultHeaders)
        headers["Authorization"] =
            "Bearer ${ekaAuthConfig.getAuthToken()}"
        headers["sdk_version"] = BuildConfig.SDK_VERSION_NAME
        return headers
    }

    override fun onSessionExpire() {
        ekaAuthConfig.sessionExpired()
    }

    override fun refreshAuthToken(url: String): Map<String, String>? {
        return runBlocking {
            val sessionToken = ekaAuthConfig.refreshToken()
            if (sessionToken.isNullOrBlank()) {
                null
            } else {
                getDefaultHeaders(url)
            }
        }
    }
}