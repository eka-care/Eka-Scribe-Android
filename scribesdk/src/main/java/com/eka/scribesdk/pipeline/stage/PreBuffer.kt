package com.eka.scribesdk.pipeline.stage

import com.eka.scribesdk.recorder.AudioFrame
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free ring buffer that decouples the real-time audio thread
 * from the coroutine-based pipeline. The audio thread calls [write]
 * which never blocks; the producer coroutine calls [drain] to collect
 * accumulated frames.
 */
class PreBuffer(private val capacity: Int = 200) {

    private val buffer = arrayOfNulls<AudioFrame>(capacity)
    private val writeIndex = AtomicInteger(0)
    private val readIndex = AtomicInteger(0)
    private val count = AtomicInteger(0)

    /**
     * Non-blocking write from the audio thread.
     * Returns false if the buffer is full (frame dropped).
     */
    fun write(frame: AudioFrame): Boolean {
        if (count.get() >= capacity) return false
        val idx = writeIndex.getAndUpdate { (it + 1) % capacity }
        buffer[idx] = frame
        count.incrementAndGet()
        return true
    }

    /**
     * Drains all available frames from the buffer.
     * Called from the producer coroutine.
     */
    fun drain(): List<AudioFrame> {
        val available = count.get()
        if (available == 0) return emptyList()

        val frames = mutableListOf<AudioFrame>()
        for (i in 0 until available) {
            val idx = readIndex.getAndUpdate { (it + 1) % capacity }
            buffer[idx]?.let { frames.add(it) }
            buffer[idx] = null
        }
        count.addAndGet(-frames.size)
        return frames
    }

    fun size(): Int = count.get()

    fun isFull(): Boolean = count.get() >= capacity

    fun clear() {
        writeIndex.set(0)
        readIndex.set(0)
        count.set(0)
        for (i in buffer.indices) buffer[i] = null
    }
}
