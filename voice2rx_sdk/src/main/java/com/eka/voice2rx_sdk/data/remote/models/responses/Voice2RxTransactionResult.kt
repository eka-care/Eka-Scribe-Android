package com.eka.voice2rx_sdk.data.remote.models.responses


import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxTransactionResult(
    @SerializedName("data")
    val data: Data?,
    @SerializedName("error")
    val error: List<ResultError?>? = null,
    @SerializedName("warning")
    val warning: List<ResultError>? = null,
    @SerializedName("message")
    val message: String? = null,
    @SerializedName("status")
    val status: Voice2RxStatus? = Voice2RxStatus.IN_PROGRESS
)

@Keep
data class ResultError(
    @SerializedName("code")
    val code: String? = null,
    @SerializedName("message")
    val message: String? = null,
)

@Keep
enum class Voice2RxStatus {
    @SerializedName("in-progress")
    IN_PROGRESS,

    @SerializedName("init")
    INIT,

    @SerializedName("stop")
    STOP,

    @SerializedName("commit")
    COMMIT,

    @SerializedName("success")
    SUCCESS,

    @SerializedName("failure")
    FAILURE,

    @SerializedName("partial_complete")
    PARTIAL_COMPLETED;

    companion object {
        fun fromValue(value: String): Voice2RxStatus {
            return Voice2RxStatus.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: IN_PROGRESS
        }
    }
}