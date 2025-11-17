package com.eka.voice2rx_sdk

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioCombinerTest {

    @Mock
    private lateinit var context: Context

    private lateinit var audioCombiner: AudioCombiner

    @Before
    fun setup() {
        MockitoAnnotations.openMocks(this)
        audioCombiner = AudioCombiner()
    }

    @Test
    fun `createWavHeader should generate correct WAV header`() {
        val dataSize = 1000
        val sampleRate = 16000

        val header = audioCombiner.createWavHeader(dataSize, sampleRate)

        assertEquals(44, header.size)

        // Check RIFF signature
        assertEquals("RIFF", String(header, 0, 4))

        // Check WAVE signature
        assertEquals("WAVE", String(header, 8, 4))

        // Check fmt signature
        assertEquals("fmt ", String(header, 12, 4))

        // Check data signature
        assertEquals("data", String(header, 36, 4))
    }

    @Test
    fun `createWavHeader should handle different sample rates`() {
        val dataSize = 2000
        val sampleRate1 = 8000
        val sampleRate2 = 44100

        val header1 = audioCombiner.createWavHeader(dataSize, sampleRate1)
        val header2 = audioCombiner.createWavHeader(dataSize, sampleRate2)

        assertEquals(44, header1.size)
        assertEquals(44, header2.size)

        // Both should be valid WAV headers
        assertEquals("RIFF", String(header1, 0, 4))
        assertEquals("RIFF", String(header2, 0, 4))
    }

    @Test
    fun `createWavHeader should handle zero data size`() {
        val header = audioCombiner.createWavHeader(0, 16000)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
    }

    @Test
    fun `createWavHeader should have correct structure`() {
        val dataSize = 4000
        val sampleRate = 16000
        val header = audioCombiner.createWavHeader(dataSize, sampleRate)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // Skip RIFF
        buffer.position(4)
        val totalDataLen = buffer.int
        assertEquals(dataSize + 36, totalDataLen)

        // Skip WAVE and fmt
        buffer.position(20)
        val subChunkSize = buffer.int
        assertEquals(16, subChunkSize)

        val audioFormat = buffer.short
        assertEquals(1, audioFormat.toInt()) // PCM

        val channels = buffer.short
        assertEquals(1, channels.toInt()) // Mono

        val sampleRateFromHeader = buffer.int
        assertEquals(sampleRate, sampleRateFromHeader)

        val blockAlign = buffer.short
        assertEquals(2, blockAlign.toInt()) // 16-bit mono

        val bitsPerSample = buffer.short
        assertEquals(16, bitsPerSample.toInt())
    }

    @Test
    fun `createWavHeader should calculate byte rate correctly`() {
        val dataSize = 1000
        val sampleRate = 16000
        val header = audioCombiner.createWavHeader(dataSize, sampleRate)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(28) // Position to byte rate field

        val byteRate = buffer.int
        val expectedByteRate = sampleRate * 2 // 16-bit mono
        assertEquals(expectedByteRate, byteRate)
    }

    @Test
    fun `createWavHeader should handle large data sizes`() {
        val dataSize = Int.MAX_VALUE / 2
        val sampleRate = 44100

        val header = audioCombiner.createWavHeader(dataSize, sampleRate)

        assertEquals(44, header.size)
        assertEquals("RIFF", String(header, 0, 4))
        assertEquals("WAVE", String(header, 8, 4))
        assertEquals("fmt ", String(header, 12, 4))
        assertEquals("data", String(header, 36, 4))
    }

    @Test
    fun `createWavHeader should handle minimum values`() {
        val dataSize = 1
        val sampleRate = 8000

        val header = audioCombiner.createWavHeader(dataSize, sampleRate)

        assertEquals(44, header.size)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4)
        val totalDataLen = buffer.int
        assertEquals(37, totalDataLen) // dataSize(1) + 36
    }

    @Test
    fun `createWavHeader should maintain consistent format`() {
        val testCases = listOf(
            Pair(100, 8000),
            Pair(1000, 16000),
            Pair(10000, 22050),
            Pair(100000, 44100),
            Pair(1000000, 48000)
        )

        testCases.forEach { (dataSize, sampleRate) ->
            val header = audioCombiner.createWavHeader(dataSize, sampleRate)

            assertEquals("Header size should be 44 bytes", 44, header.size)
            assertEquals("RIFF signature", "RIFF", String(header, 0, 4))
            assertEquals("WAVE signature", "WAVE", String(header, 8, 4))
            assertEquals("fmt signature", "fmt ", String(header, 12, 4))
            assertEquals("data signature", "data", String(header, 36, 4))
        }
    }

    @Test
    fun `stopPlaying should execute without exceptions`() {
        // Since stopPlaying() works with private fields and MediaPlayer instances,
        // we can at least verify the method doesn't throw exceptions
        try {
            audioCombiner.stopPlaying()
            // Test passes if no exception is thrown
            assertTrue(true)
        } catch (e: Exception) {
            fail("stopPlaying() should not throw exceptions: ${e.message}")
        }
    }

    @Test
    fun `audioCombiner should be instantiable`() {
        val newAudioCombiner = AudioCombiner()
        assertNotNull(newAudioCombiner)
    }

    @Test
    fun `createWavHeader should produce different headers for different inputs`() {
        val header1 = audioCombiner.createWavHeader(1000, 16000)
        val header2 = audioCombiner.createWavHeader(2000, 16000)
        val header3 = audioCombiner.createWavHeader(1000, 22050)

        // Headers should be different when parameters change
        assertFalse(
            "Headers with different data sizes should differ",
            header1.contentEquals(header2)
        )
        assertFalse(
            "Headers with different sample rates should differ",
            header1.contentEquals(header3)
        )
    }
}