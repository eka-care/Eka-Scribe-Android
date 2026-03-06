package com.eka.scribesdk.session

import com.eka.scribesdk.data.remote.models.responses.ScribeResultResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TransactionResultTest {

    // =====================================================================
    // TransactionResult
    // =====================================================================

    @Test
    fun `Success with default values`() {
        val result = TransactionResult.Success()
        assertEquals("", result.folderName)
        assertEquals("", result.bid)
        assertTrue(result is TransactionResult)
    }

    @Test
    fun `Success with custom values`() {
        val result = TransactionResult.Success(folderName = "260302", bid = "bid-1")
        assertEquals("260302", result.folderName)
        assertEquals("bid-1", result.bid)
    }

    @Test
    fun `Error contains message`() {
        val result = TransactionResult.Error("something broke")
        assertEquals("something broke", result.message)
        assertTrue(result is TransactionResult)
    }

    @Test
    fun `sealed class exhaustive when check`() {
        val results: List<TransactionResult> = listOf(
            TransactionResult.Success("f", "b"),
            TransactionResult.Error("e")
        )
        for (result in results) {
            when (result) {
                is TransactionResult.Success -> assertTrue(true)
                is TransactionResult.Error -> assertTrue(true)
            }
        }
    }

    // =====================================================================
    // TransactionPollResult
    // =====================================================================

    @Test
    fun `PollResult Success contains result`() {
        val response = ScribeResultResponse(data = null)
        val result = TransactionPollResult.Success(response)
        assertEquals(response, result.result)
        assertTrue(result is TransactionPollResult)
    }

    @Test
    fun `PollResult Failed contains error message`() {
        val result = TransactionPollResult.Failed("transcription failed")
        assertEquals("transcription failed", result.error)
    }

    @Test
    fun `PollResult Timeout is object`() {
        val result = TransactionPollResult.Timeout
        assertTrue(result is TransactionPollResult)
    }

    @Test
    fun `sealed class exhaustive for poll results`() {
        val results: List<TransactionPollResult> = listOf(
            TransactionPollResult.Success(ScribeResultResponse(data = null)),
            TransactionPollResult.Failed("err"),
            TransactionPollResult.Timeout
        )
        for (result in results) {
            when (result) {
                is TransactionPollResult.Success -> assertTrue(true)
                is TransactionPollResult.Failed -> assertTrue(true)
                is TransactionPollResult.Timeout -> assertTrue(true)
            }
        }
    }
}
