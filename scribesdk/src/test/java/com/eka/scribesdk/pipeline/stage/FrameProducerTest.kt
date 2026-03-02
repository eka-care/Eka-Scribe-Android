package com.eka.scribesdk.pipeline.stage

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameProducerTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
    }

    private val logger = NoOpLogger()

    private fun makeFrame(index: Long): AudioFrame {
        return AudioFrame(
            pcm = ShortArray(FRAME_SIZE),
            timestampMs = index,
            sampleRate = SAMPLE_RATE,
            frameIndex = index
        )
    }

    @Test
    fun `frames in PreBuffer are forwarded to frameChannel`() = runTest {
        val preBuffer = PreBuffer(capacity = 100)
        val channel = Channel<AudioFrame>(100)
        val producer = FrameProducer(preBuffer, channel, logger)

        // Write frames before starting producer
        for (i in 0L until 10L) {
            preBuffer.write(makeFrame(i))
        }

        producer.start(this)

        // Collect frames from channel
        val received = mutableListOf<AudioFrame>()
        val collectJob = launch {
            for (frame in channel) {
                received.add(frame)
            }
        }

        // Stop producer — this will drain remaining and close channel
        producer.stopAndDrain()
        collectJob.join()

        assertEquals("All 10 frames should be forwarded", 10, received.size)
    }

    @Test
    fun `frame order preserved through producer`() = runTest {
        val preBuffer = PreBuffer(capacity = 100)
        val channel = Channel<AudioFrame>(100)
        val producer = FrameProducer(preBuffer, channel, logger)

        for (i in 0L until 20L) {
            preBuffer.write(makeFrame(i))
        }

        producer.start(this)

        val received = mutableListOf<AudioFrame>()
        val collectJob = launch {
            for (frame in channel) {
                received.add(frame)
            }
        }

        producer.stopAndDrain()
        collectJob.join()

        assertEquals(20, received.size)
        for (i in 0 until 20) {
            assertEquals(
                "Frame at position $i should have index $i",
                i.toLong(),
                received[i].frameIndex
            )
        }
    }

    @Test
    fun `stopAndDrain sends remaining frames then closes channel`() = runTest {
        val preBuffer = PreBuffer(capacity = 100)
        val channel = Channel<AudioFrame>(100)
        val producer = FrameProducer(preBuffer, channel, logger)

        producer.start(this)

        // Write frames AFTER producer started — simulates real-time recording
        for (i in 0L until 5L) {
            preBuffer.write(makeFrame(i))
        }

        // Give producer a moment to drain
        kotlinx.coroutines.delay(50)

        // Write more frames
        for (i in 5L until 10L) {
            preBuffer.write(makeFrame(i))
        }

        val received = mutableListOf<AudioFrame>()
        val collectJob = launch {
            for (frame in channel) {
                received.add(frame)
            }
        }

        producer.stopAndDrain()
        collectJob.join()

        assertEquals("All 10 frames should arrive", 10, received.size)
    }

    @Test
    fun `empty PreBuffer plus stopAndDrain closes channel without error`() = runTest {
        val preBuffer = PreBuffer(capacity = 100)
        val channel = Channel<AudioFrame>(100)
        val producer = FrameProducer(preBuffer, channel, logger)

        producer.start(this)

        val received = mutableListOf<AudioFrame>()
        val collectJob = launch {
            for (frame in channel) {
                received.add(frame)
            }
        }

        // Stop immediately with empty buffer
        producer.stopAndDrain()
        collectJob.join()

        assertTrue("No frames should be received", received.isEmpty())
    }

    private class NoOpLogger : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }
}
