package com.eka.scribesdk.data.local.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

internal class TransactionStageTest {

    @Test
    fun `all 7 stages exist`() {
        val stages = TransactionStage.entries
        assertEquals(7, stages.size)
    }

    @Test
    fun `valueOf works for all stages`() {
        val expected =
            listOf("INIT", "STOP", "COMMIT", "ANALYZING", "COMPLETED", "FAILURE", "ERROR")
        for (name in expected) {
            assertEquals(name, TransactionStage.valueOf(name).name)
        }
    }

    @Test
    fun `ordinal values are sequential`() {
        assertEquals(0, TransactionStage.INIT.ordinal)
        assertEquals(1, TransactionStage.STOP.ordinal)
        assertEquals(2, TransactionStage.COMMIT.ordinal)
        assertEquals(3, TransactionStage.ANALYZING.ordinal)
        assertEquals(4, TransactionStage.COMPLETED.ordinal)
        assertEquals(5, TransactionStage.FAILURE.ordinal)
        assertEquals(6, TransactionStage.ERROR.ordinal)
    }
}
