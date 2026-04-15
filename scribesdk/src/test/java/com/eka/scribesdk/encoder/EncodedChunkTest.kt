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
    fun `EncodedChunk with MP3 format`() {
        val chunk = EncodedChunk(
            filePath = "/tmp/output.mp3",
            format = AudioFormat.MP3,
            sizeBytes = 16000L,
            durationMs = 5000L
        )
        assertEquals(AudioFormat.MP3, chunk.format)
    }

    @Test
    fun `AudioFormat has exactly 2 values`() {
        assertEquals(2, AudioFormat.entries.size)
        assertEquals("WAV", AudioFormat.WAV.name)
        assertEquals("MP3", AudioFormat.MP3.name)
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
        val copy = original.copy(format = AudioFormat.MP3)
        assertEquals(AudioFormat.MP3, copy.format)
        assertEquals("/tmp/a.wav", copy.filePath)
    }
}
