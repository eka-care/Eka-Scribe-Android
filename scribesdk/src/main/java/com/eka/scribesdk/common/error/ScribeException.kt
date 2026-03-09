package com.eka.scribesdk.common.error

class ScribeException(
    val code: ErrorCode,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
