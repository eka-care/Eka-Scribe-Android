package com.eka.scribesdk.session

import kotlinx.coroutines.delay

/**
 * Counts recorded time (only while [isRecording]; pauses don't count) and calls [onLimit] once it
 * reaches [limitMs]. Returns when the limit fires or [isActive] turns false (session stopped,
 * cancelled or failed). Sessions API discovery caps a session at lite 10 min / pro 60 min.
 */
internal class SessionDurationLimiter(
    private val limitMs: Long,
    private val isRecording: () -> Boolean,
    private val isActive: () -> Boolean,
    private val tickMs: Long = DEFAULT_TICK_MS,
    private val onLimit: () -> Unit
) {
    companion object {
        private const val DEFAULT_TICK_MS = 1_000L
    }

    suspend fun run() {
        var recordedMs = 0L
        while (isActive()) {
            delay(tickMs)
            if (isRecording()) recordedMs += tickMs
            if (recordedMs >= limitMs) {
                onLimit()
                return
            }
        }
    }
}
