package com.eka.scribesdk.data.local.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class AudioChunkEntityExtrasTest {

    @Test
    fun `default uploadState is PENDING`() {
        val chunk = AudioChunkEntity(
            chunkId = "c1",
            sessionId = "s1",
            chunkIndex = 0,
            filePath = "/tmp/c1.m4a",
            fileName = "c1.m4a",
            startTimeMs = 0,
            endTimeMs = 10000,
            durationMs = 10000,
            createdAt = 1000L
        )
        assertEquals(UploadState.PENDING.name, chunk.uploadState)
    }

    @Test
    fun `default retryCount is 0`() {
        val chunk = AudioChunkEntity(
            chunkId = "c1", sessionId = "s1", chunkIndex = 0,
            filePath = "/tmp/c1.m4a", fileName = "c1.m4a",
            startTimeMs = 0, endTimeMs = 5000, durationMs = 5000,
            createdAt = 1000L
        )
        assertEquals(0, chunk.retryCount)
    }

    @Test
    fun `default qualityScore is null`() {
        val chunk = AudioChunkEntity(
            chunkId = "c1", sessionId = "s1", chunkIndex = 0,
            filePath = "/tmp/c1.m4a", fileName = "c1.m4a",
            startTimeMs = 0, endTimeMs = 5000, durationMs = 5000,
            createdAt = 1000L
        )
        assertNull(chunk.qualityScore)
    }

    @Test
    fun `explicit values override defaults`() {
        val chunk = AudioChunkEntity(
            chunkId = "c1", sessionId = "s1", chunkIndex = 2,
            filePath = "/tmp/c1.m4a", fileName = "c1.m4a",
            startTimeMs = 5000, endTimeMs = 15000, durationMs = 10000,
            uploadState = UploadState.SUCCESS.name,
            retryCount = 3,
            qualityScore = 0.95f,
            createdAt = 1000L
        )
        assertEquals(UploadState.SUCCESS.name, chunk.uploadState)
        assertEquals(3, chunk.retryCount)
        assertEquals(0.95f, chunk.qualityScore!!, 0.001f)
        assertEquals(2, chunk.chunkIndex)
        assertEquals(5000L, chunk.startTimeMs)
        assertEquals(15000L, chunk.endTimeMs)
        assertEquals(10000L, chunk.durationMs)
    }

    @Test
    fun `copy preserves all fields`() {
        val original = AudioChunkEntity(
            chunkId = "c1", sessionId = "s1", chunkIndex = 0,
            filePath = "/tmp/c1.m4a", fileName = "c1.m4a",
            startTimeMs = 0, endTimeMs = 5000, durationMs = 5000,
            createdAt = 1000L
        )
        val copy = original.copy(retryCount = 5, uploadState = UploadState.FAILED.name)
        assertEquals(5, copy.retryCount)
        assertEquals(UploadState.FAILED.name, copy.uploadState)
        assertEquals("c1", copy.chunkId)
        assertEquals("s1", copy.sessionId)
    }
}
