package com.eka.scribesdk.api.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ScribeHistoryItem(
    @SerializedName("bId")
    val bId: String?,
    @SerializedName("createdAt")
    val createdAt: String?,
    @SerializedName("flavour")
    val flavour: String?,
    @SerializedName("mode")
    val mode: String?,
    @SerializedName("oid")
    val oid: String?,
    @SerializedName("processingStatus")
    val processingStatus: String?,
    @SerializedName("txnId")
    val txnId: String?,
    @SerializedName("userStatus")
    val userStatus: String?,
    @SerializedName("uuid")
    val uuid: String?,
    @SerializedName("version")
    val version: String?,
    @SerializedName("patientDetails")
    val patientDetails: ScribePatientInfo?,
)

@Keep
data class ScribePatientInfo(
    @SerializedName("age")
    val age: Int?,
    @SerializedName("biologicalSex")
    val biologicalSex: String?,
    @SerializedName("name")
    val name: String?,
    @SerializedName("patientId")
    val patientId: String?,
    @SerializedName("visitId")
    val visitId: String?,
)
