package com.eka.voice2rx_sdk.data.remote.models.responses

import androidx.annotation.Keep
import com.eka.voice2rx_sdk.data.local.models.Voice2RxType
import com.google.gson.annotations.SerializedName

@Keep
data class Voice2RxHistoryResponse(
    @SerializedName("data")
    var data: List<Voice2RxHistoryItem>?
)

@Keep
data class Voice2RxHistoryItem(
    @SerializedName("b_id")
    var bId: String?,
    @SerializedName("created_at")
    var createdAt: String?,
    @SerializedName("flavour")
    var flavour: String?,
    @SerializedName("mode")
    var mode: Voice2RxType?,
    @SerializedName("oid")
    var oid: String?,
    @SerializedName("processing_status")
    var processingStatus: ProcessingStatus?,
    @SerializedName("txn_id")
    var txnId: String?,
    @SerializedName("user_status")
    var userStatus: UserStatus?,
    @SerializedName("uuid")
    var uuid: String?,
    @SerializedName("version")
    var version: String?
)

@Keep
enum class UserStatus(val value: String) {
    @SerializedName("init")
    INIT("init"),
    @SerializedName("commit")
    COMMIT("commit"),
    @SerializedName("stopped")
    STOPPED("stopped"),
    @SerializedName("cancelled")
    CANCELLED("cancelled")
}

@Keep
enum class ProcessingStatus(val value: String) {
    @SerializedName("in-progress")
    IN_PROGRESS("in-progress"),
    @SerializedName("success")
    SUCCESS("success"),
    @SerializedName("system_failure")
    SYSTEM_FAILURE("system_failure"),
    @SerializedName("request_failure")
    REQUEST_FAILURE("request_failure"),
    @SerializedName("cancelled")
    CANCELLED("cancelled")
}
