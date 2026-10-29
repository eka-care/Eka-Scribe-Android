package com.eka.scribesdk.data.remote.models.responses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal class HistoryResponseMapperTest {

    @Test
    fun `toScribeHistoryItem maps all fields`() {
        val item = HistoryItem(
            bId = "bid-1",
            createdAt = "2025-01-01",
            flavour = "lite",
            mode = "opd",
            oid = "oid-1",
            processingStatus = "completed",
            txnId = "txn-1",
            userStatus = "active",
            uuid = "uuid-1",
            version = "v2",
            patientDetails = PatientDetailsInfo(
                age = "30",
                biologicalSex = "male",
                name = "John Doe",
                patientId = "p-1",
                visitId = "v-1"
            )
        )

        val result = item.toScribeHistoryItem()

        assertEquals("bid-1", result.bId)
        assertEquals("2025-01-01", result.createdAt)
        assertEquals("lite", result.flavour)
        assertEquals("opd", result.mode)
        assertEquals("oid-1", result.oid)
        assertEquals("completed", result.processingStatus)
        assertEquals("txn-1", result.txnId)
        assertEquals("active", result.userStatus)
        assertEquals("uuid-1", result.uuid)
        assertEquals("v2", result.version)

        val patient = result.patientDetails
        assertNotNull(patient)
        assertEquals(30, patient!!.age)
        assertEquals("male", patient.biologicalSex)
        assertEquals("John Doe", patient.name)
        assertEquals("p-1", patient.patientId)
        assertEquals("v-1", patient.visitId)
    }

    @Test
    fun `toScribeHistoryItem with null patient details`() {
        val item = HistoryItem(
            bId = "bid-2",
            createdAt = null,
            flavour = null,
            mode = null,
            oid = null,
            processingStatus = null,
            txnId = null,
            userStatus = null,
            uuid = null,
            version = null,
            patientDetails = null
        )

        val result = item.toScribeHistoryItem()

        assertEquals("bid-2", result.bId)
        assertNull(result.createdAt)
        assertNull(result.patientDetails)
    }

    @Test
    fun `toScribeHistoryItem with null patient fields`() {
        val item = HistoryItem(
            bId = null,
            createdAt = null,
            flavour = null,
            mode = null,
            oid = null,
            processingStatus = null,
            txnId = null,
            userStatus = null,
            uuid = null,
            version = null,
            patientDetails = PatientDetailsInfo(
                age = null,
                biologicalSex = null,
                name = null,
                patientId = null,
                visitId = null
            )
        )

        val result = item.toScribeHistoryItem()
        val patient = result.patientDetails
        assertNotNull(patient)
        assertNull(patient!!.age)
        assertNull(patient.biologicalSex)
        assertNull(patient.name)
    }

    @Test
    fun `history from web and desktop parses with string or blank age and name or gender keys`() {
        val json = """
            {"status": "success", "data": [
              {"txn_id": "sc-1", "flavour": "ekascribe-web",
               "patient_details": {"name": "Test Patx001", "age": "23", "gender": "M", "mobile": 7907484532}},
              {"txn_id": "sc-2", "flavour": "ekascribe-web",
               "patient_details": {"name": "No Age", "age": "", "gender": ""}},
              {"txn_id": "a-3", "flavour": "android",
               "patient_details": {"username": "Legacy Android", "age": 41, "biologicalSex": "F"}}
            ]}
        """.trimIndent()

        val items = com.google.gson.Gson().fromJson(json, HistoryResponse::class.java)
            .data!!.map { it!!.toScribeHistoryItem() }

        assertEquals("Test Patx001", items[0].patientDetails?.name)
        assertEquals(23, items[0].patientDetails?.age)
        assertEquals("M", items[0].patientDetails?.biologicalSex)
        assertEquals("No Age", items[1].patientDetails?.name)
        assertNull(items[1].patientDetails?.age)
        assertEquals("Legacy Android", items[2].patientDetails?.name)
        assertEquals(41, items[2].patientDetails?.age)
        assertEquals("F", items[2].patientDetails?.biologicalSex)
    }
}
