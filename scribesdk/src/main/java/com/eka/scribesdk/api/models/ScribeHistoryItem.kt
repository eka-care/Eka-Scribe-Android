package com.eka.scribesdk.api.models

import androidx.annotation.Keep

@Keep
data class ScribeHistoryItem(
    val bId: String?,
    val createdAt: String?,
    val flavour: String?,
    val mode: String?,
    val oid: String?,
    val processingStatus: String?,
    val txnId: String?,
    val userStatus: String?,
    val uuid: String?,
    val version: String?,
    val patientDetails: ScribePatientInfo?,
)

@Keep
data class ScribePatientInfo(
    val age: Int?,
    val biologicalSex: String?,
    val name: String?,
    val patientId: String?,
    val visitId: String?,
)
