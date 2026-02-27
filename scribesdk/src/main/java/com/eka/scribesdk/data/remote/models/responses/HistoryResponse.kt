package com.eka.scribesdk.data.remote.models.responses

import androidx.annotation.Keep
import com.eka.scribesdk.api.models.ScribeHistoryItem
import com.eka.scribesdk.api.models.ScribePatientInfo
import com.google.gson.annotations.SerializedName

@Keep
internal data class HistoryResponse(
    @SerializedName("data")
    var data: List<HistoryItem>?
)

@Keep
internal data class HistoryItem(
    @SerializedName("b_id")
    var bId: String?,
    @SerializedName("created_at")
    var createdAt: String?,
    @SerializedName("flavour")
    var flavour: String?,
    @SerializedName("mode")
    var mode: String?,
    @SerializedName("oid")
    var oid: String?,
    @SerializedName("processing_status")
    var processingStatus: String?,
    @SerializedName("txn_id")
    var txnId: String?,
    @SerializedName("user_status")
    var userStatus: String?,
    @SerializedName("uuid")
    var uuid: String?,
    @SerializedName("version")
    var version: String?,
    @SerializedName("patient_details")
    var patientDetails: PatientDetailsInfo?
)

@Keep
internal data class PatientDetailsInfo(
    @SerializedName("age")
    var age: Int?,
    @SerializedName("biological_sex")
    var biologicalSex: String?,
    @SerializedName("name")
    var name: String?,
    @SerializedName("patient_id")
    var patientId: String?,
    @SerializedName("visit_id")
    var visitId: String?
)

internal fun HistoryItem.toScribeHistoryItem(): ScribeHistoryItem {
    return ScribeHistoryItem(
        bId = bId,
        createdAt = createdAt,
        flavour = flavour,
        mode = mode,
        oid = oid,
        processingStatus = processingStatus,
        txnId = txnId,
        userStatus = userStatus,
        uuid = uuid,
        version = version,
        patientDetails = patientDetails?.let {
            ScribePatientInfo(
                age = it.age,
                biologicalSex = it.biologicalSex,
                name = it.name,
                patientId = it.patientId,
                visitId = it.visitId,
            )
        },
    )
}
