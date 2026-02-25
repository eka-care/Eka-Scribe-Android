package com.eka.scribesdk.pipeline.stage

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drains frames from [PreBuffer] and sends them to the [frameChannel].
 * Runs as a coroutine on Dispatchers.Default.
 * Suspends on send() when the channel is full (backpressure).
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

    fun start(scope: CoroutineScope) {
        job = scope.launch(Dispatchers.Default) {
            logger.debug(TAG, "FrameProducer started")
            while (isActive) {
                val frames = preBuffer.drain()
                if (frames.isEmpty()) {
                    delay(DRAIN_INTERVAL_MS)
                    continue
                }
                for (frame in frames) {
                    frameChannel.send(frame)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        logger.debug(TAG, "FrameProducer stopped")
    }
}
