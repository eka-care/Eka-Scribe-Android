package com.eka.scribesdk.api.models

import com.eka.scribesdk.common.error.ErrorCode

data class ScribeError(
    val code: ErrorCode,
    val message: String,
    val isRecoverable: Boolean = false
)
