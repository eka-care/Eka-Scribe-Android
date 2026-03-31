package com.eka.scribesdk.data.remote.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadMetadataTest {

    @Test
    fun `default mimeType is audio wav`() {
        val metadata = UploadMetadata(
            chunkId = "c1",
            sessionId = "s1",
            chunkIndex = 0,
            fileName = "chunk.wav",
            folderName = "260302",
            bid = "bid-1"
        )
        assertEquals("audio/mpeg", metadata.mimeType)
    }

    @Test
    fun `custom mimeType overrides default`() {
        val metadata = UploadMetadata(
            chunkId = "c1",
            sessionId = "s1",
            chunkIndex = 0,
            fileName = "chunk.mp3",
            folderName = "260302",
            bid = "bid-1",
            mimeType = "audio/mpeg"
        )
        assertEquals("audio/mpeg", metadata.mimeType)
    }

    @Test
    fun `all fields stored correctly`() {
        val metadata = UploadMetadata(
            chunkId = "chunk-42",
            sessionId = "session-99",
            chunkIndex = 5,
            fileName = "5.mp3",
            folderName = "260302",
            bid = "bid-xyz"
        )
        assertEquals("chunk-42", metadata.chunkId)
        assertEquals("session-99", metadata.sessionId)
        assertEquals(5, metadata.chunkIndex)
        assertEquals("5.mp3", metadata.fileName)
        assertEquals("260302", metadata.folderName)
        assertEquals("bid-xyz", metadata.bid)
    }

    @Test
    fun `data class equality`() {
        val a = UploadMetadata("c1", "s1", 0, "f.wav", "260302", "bid")
        val b = UploadMetadata("c1", "s1", 0, "f.wav", "260302", "bid")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
