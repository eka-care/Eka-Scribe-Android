package com.eka.voice2rx_sdk.networking

import okhttp3.Request

enum class AuthorizationType {
    ACCESS_TOKEN,
    NONE;
}

fun Request.authTag(): AuthorizationType =
    tag(AuthorizationType::class.java) ?: AuthorizationType.ACCESS_TOKEN