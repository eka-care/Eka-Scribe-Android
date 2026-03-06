package com.eka.scribesdk.pipeline.stage

import com.eka.scribesdk.recorder.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreBufferTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
    }

    private fun makeFrame(index: Long): AudioFrame {
        return AudioFrame(
            pcm = ShortArray(FRAME_SIZE),
            timestampMs = index,
            sampleRate = SAMPLE_RATE,
            frameIndex = index
        )
    }

    // =====================================================================
    // BASIC OPERATIONS
    // =====================================================================

    @Test
    fun `write and drain single frame`() {
        val buffer = PreBuffer(capacity = 10)
        val frame = makeFrame(0)

        assertTrue(buffer.write(frame))
        val drained = buffer.drain()

        assertEquals(1, drained.size)
        assertEquals(0L, drained[0].frameIndex)
        assertEquals(0, buffer.size())
    }

    @Test
    fun `write and drain multiple frames preserves FIFO order`() {
        val buffer = PreBuffer(capacity = 10)

        for (i in 0L until 5L) {
            assertTrue(buffer.write(makeFrame(i)))
        }

        val drained = buffer.drain()
        assertEquals(5, drained.size)
        for (i in 0 until 5) {
            assertEquals("Frame $i should be at position $i", i.toLong(), drained[i].frameIndex)
        }
    }

    @Test
    fun `drain empty buffer returns empty list`() {
        val buffer = PreBuffer(capacity = 10)
        val drained = buffer.drain()
        assertTrue(drained.isEmpty())
    }

    // =====================================================================
    // OVERFLOW / CAPACITY
    // =====================================================================

    @Test
    fun `write returns false when buffer is full`() {
        val capacity = 5
        val buffer = PreBuffer(capacity = capacity)

        // Fill to capacity
        for (i in 0L until capacity.toLong()) {
            assertTrue("Write $i should succeed", buffer.write(makeFrame(i)))
        }

        // Next write should fail
        assertFalse("Write beyond capacity should return false", buffer.write(makeFrame(99)))
        assertTrue(buffer.isFull())
    }

    @Test
    fun `new writes succeed after drain recovers from full state`() {
        val capacity = 5
        val buffer = PreBuffer(capacity = capacity)

        // Fill to capacity
        for (i in 0L until capacity.toLong()) {
            buffer.write(makeFrame(i))
        }
        assertTrue(buffer.isFull())

        // Drain
        buffer.drain()
        assertFalse(buffer.isFull())
        assertEquals(0, buffer.size())

        // Write again
        assertTrue("Write should succeed after drain", buffer.write(makeFrame(100)))
        assertEquals(1, buffer.size())
    }

    @Test
    fun `ring buffer wrap-around - fill drain fill again`() {
        val capacity = 5
        val buffer = PreBuffer(capacity = capacity)

        // First fill
        for (i in 0L until capacity.toLong()) {
            buffer.write(makeFrame(i))
        }
        val firstDrain = buffer.drain()
        assertEquals(capacity, firstDrain.size)

        // Second fill — indices wrap around in the internal ring array
        for (i in 10L until 10L + capacity.toLong()) {
            assertTrue(buffer.write(makeFrame(i)))
        }
        val secondDrain = buffer.drain()
        assertEquals(capacity, secondDrain.size)
        for (i in 0 until capacity) {
            assertEquals(10L + i, secondDrain[i].frameIndex)
        }
    }

    // =====================================================================
    // STATE TRACKING
    // =====================================================================

    @Test
    fun `size and isFull track correctly`() {
        val buffer = PreBuffer(capacity = 3)

        assertEquals(0, buffer.size())
        assertFalse(buffer.isFull())

        buffer.write(makeFrame(0))
        assertEquals(1, buffer.size())
        assertFalse(buffer.isFull())

        buffer.write(makeFrame(1))
        buffer.write(makeFrame(2))
        assertEquals(3, buffer.size())
        assertTrue(buffer.isFull())

        buffer.drain()
        assertEquals(0, buffer.size())
        assertFalse(buffer.isFull())
    }

    @Test
    fun `clear resets all state`() {
        val buffer = PreBuffer(capacity = 5)

        buffer.write(makeFrame(0))
        buffer.write(makeFrame(1))
        assertEquals(2, buffer.size())

        buffer.clear()
        assertEquals(0, buffer.size())
        assertFalse(buffer.isFull())

        val drained = buffer.drain()
        assertTrue(drained.isEmpty())

        // Write after clear should work
        assertTrue(buffer.write(makeFrame(10)))
        val postClearDrain = buffer.drain()
        assertEquals(1, postClearDrain.size)
        assertEquals(10L, postClearDrain[0].frameIndex)
    }

    // =====================================================================
    // INTERLEAVED OPERATIONS
    // =====================================================================

    @Test
    fun `interleaved writes and drains maintain correctness`() {
        val buffer = PreBuffer(capacity = 10)

        // Write 3, drain, write 3 more, drain — all should be in order
        buffer.write(makeFrame(0))
        buffer.write(makeFrame(1))
        buffer.write(makeFrame(2))

        val first = buffer.drain()
        assertEquals(3, first.size)

        buffer.write(makeFrame(3))
        buffer.write(makeFrame(4))
        buffer.write(makeFrame(5))

        val second = buffer.drain()
        assertEquals(3, second.size)
        assertEquals(3L, second[0].frameIndex)
        assertEquals(4L, second[1].frameIndex)
        assertEquals(5L, second[2].frameIndex)
    }

    @Test
    fun `partial drain followed by more writes`() {
        val buffer = PreBuffer(capacity = 10)

        // Write 5 frames
        for (i in 0L until 5L) {
            buffer.write(makeFrame(i))
        }
        assertEquals(5, buffer.size())

        // Drain all
        val drained = buffer.drain()
        assertEquals(5, drained.size)

        // Write 3 more
        buffer.write(makeFrame(10))
        buffer.write(makeFrame(11))
        buffer.write(makeFrame(12))

        val second = buffer.drain()
        assertEquals(3, second.size)
        assertEquals(10L, second[0].frameIndex)
    }

    @Test
    fun `overflow does not corrupt existing data`() {
        val buffer = PreBuffer(capacity = 3)

        buffer.write(makeFrame(0))
        buffer.write(makeFrame(1))
        buffer.write(makeFrame(2))

        // Overflow — should return false but not corrupt
        assertFalse(buffer.write(makeFrame(99)))
        assertFalse(buffer.write(makeFrame(100)))

        // Drain should return original 3 frames intact
        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals(0L, drained[0].frameIndex)
        assertEquals(1L, drained[1].frameIndex)
        assertEquals(2L, drained[2].frameIndex)
    }
}
