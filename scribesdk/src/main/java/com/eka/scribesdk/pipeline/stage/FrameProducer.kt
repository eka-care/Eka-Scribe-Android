package com.eka.scribesdk.pipeline.stage

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drains frames from [PreBuffer] and sends them to the [frameChannel].
 * Runs as a coroutine on Dispatchers.Default.
 *
 * On [stopAndDrain], performs one final drain of the PreBuffer,
 * sends all remaining frames, then closes the frameChannel.
 */
class FrameProducer(
    private val preBuffer: PreBuffer,
    private val frameChannel: Channel<AudioFrame>,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "FrameProducer"
        private const val DRAIN_INTERVAL_MS = 5L
    }

    private var job: Job? = null
    private val stopped = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        stopped.set(false)
        job = scope.launch(Dispatchers.Default) {
            logger.debug(TAG, "FrameProducer started")
            while (!stopped.get()) {
                val frames = preBuffer.drain()
                if (frames.isEmpty()) {
                    delay(DRAIN_INTERVAL_MS)
                    continue
                }
                for (frame in frames) {
                    frameChannel.send(frame)
                }
            }
            // Final drain after stop flag set — pick up any remaining frames
            val remaining = preBuffer.drain()
            for (frame in remaining) {
                frameChannel.send(frame)
            }
            frameChannel.close()
            logger.debug(TAG, "FrameProducer drained and closed channel")
        }
    }

    suspend fun stopAndDrain() {
        stopped.set(true)
        job?.join()
        job = null
        logger.debug(TAG, "FrameProducer stopped after drain")
    }
}
