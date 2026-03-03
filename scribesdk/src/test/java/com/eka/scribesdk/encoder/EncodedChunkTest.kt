package com.eka.scribesdk.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class EncodedChunkTest {

    @Test
    fun `EncodedChunk stores all fields`() {
        val chunk = EncodedChunk(
            filePath = "/tmp/output.wav",
            format = AudioFormat.WAV,
            sizeBytes = 32000L,
            durationMs = 10000L
        )
        assertEquals("/tmp/output.wav", chunk.filePath)
        assertEquals(AudioFormat.WAV, chunk.format)
        assertEquals(32000L, chunk.sizeBytes)
        assertEquals(10000L, chunk.durationMs)
    }

    @Test
    fun `EncodedChunk with M4A format`() {
        val chunk = EncodedChunk(
            filePath = "/tmp/output.m4a",
            format = AudioFormat.M4A,
            sizeBytes = 16000L,
            durationMs = 5000L
        )
        assertEquals(AudioFormat.M4A, chunk.format)
    }

    @Test
    fun `AudioFormat has exactly 2 values`() {
        assertEquals(2, AudioFormat.entries.size)
        assertEquals("WAV", AudioFormat.WAV.name)
        assertEquals("M4A", AudioFormat.M4A.name)
    }

    @Test
    fun `data class equality`() {
        val a = EncodedChunk("/tmp/a.wav", AudioFormat.WAV, 100, 1000)
        val b = EncodedChunk("/tmp/a.wav", AudioFormat.WAV, 100, 1000)
        assertEquals(a, b)
    }

    @Test
    fun `copy works correctly`() {
        val original = EncodedChunk("/tmp/a.wav", AudioFormat.WAV, 100, 1000)
        val copy = original.copy(format = AudioFormat.M4A)
        assertEquals(AudioFormat.M4A, copy.format)
        assertEquals("/tmp/a.wav", copy.filePath)
    }
}
