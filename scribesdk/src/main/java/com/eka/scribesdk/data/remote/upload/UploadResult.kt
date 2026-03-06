package com.eka.scribesdk.data.remote.upload

sealed class UploadResult {
    data class Success(val url: String) : UploadResult()
    data class Failure(val error: String, val isRetryable: Boolean) : UploadResult()
}
