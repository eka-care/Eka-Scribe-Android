package com.eka.voice2rx_sdk.sdkinit.ekaauth

interface EkaAuthConfig {
    suspend fun refreshToken(): String
    fun sessionExpired()
}