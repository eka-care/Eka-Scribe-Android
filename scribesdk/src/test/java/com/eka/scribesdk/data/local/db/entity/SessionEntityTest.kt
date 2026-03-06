package com.eka.scribesdk.data.local.db.entity

import com.eka.scribesdk.api.models.UploadStage
import org.junit.Assert.assertEquals
import org.junit.Test

internal class SessionEntityTest {

    @Test
    fun `toScribeSession maps all fields correctly`() {
        val entity = SessionEntity(
            sessionId = "s1",
            createdAt = 1000L,
            updatedAt = 2000L,
            state = "RECORDING",
            chunkCount = 5,
            uploadStage = TransactionStage.ANALYZING.name
        )

        val session = entity.toScribeSession()

        assertEquals("s1", session.sessionId)
        assertEquals(1000L, session.createdAt)
        assertEquals(2000L, session.updatedAt)
        assertEquals("RECORDING", session.state)
        assertEquals(5, session.chunkCount)
        assertEquals(UploadStage.ANALYZING, session.uploadStage)
    }

    @Test
    fun `toScribeSession defaults to INIT for invalid upload stage`() {
        val entity = SessionEntity(
            sessionId = "s2",
            createdAt = 0L,
            updatedAt = 0L,
            state = "IDLE",
            uploadStage = "INVALID_STAGE"
        )

        val session = entity.toScribeSession()

        assertEquals(UploadStage.INIT, session.uploadStage)
    }

    @Test
    fun `toScribeSession maps COMPLETED stage`() {
        val entity = SessionEntity(
            sessionId = "s3",
            createdAt = 0L,
            updatedAt = 0L,
            state = "COMPLETED",
            uploadStage = TransactionStage.COMPLETED.name
        )

        assertEquals(UploadStage.COMPLETED, entity.toScribeSession().uploadStage)
    }
}
