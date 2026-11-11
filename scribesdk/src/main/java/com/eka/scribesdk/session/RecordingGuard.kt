package com.eka.scribesdk.session

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.eka.scribesdk.api.models.RecordingAlert
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Watches a recording for the cases a doctor can't see from across the desk:
 * - forgotten End: "Still recording?" after [remindAfterMs] of recording, paused if unanswered
 *   for [pauseAfterNoAnswerMs], ended (note generated) at [maxRecordingMs] of recorded audio;
 * - low battery while not charging.
 * Another app taking the mic is handled in [SessionManager]; it only shows up here as an alert.
 */
internal class RecordingGuard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val state: StateFlow<SessionState>,
    pauseReason: StateFlow<PauseReason?>,
    private val pause: (PauseReason) -> Unit,
    private val stop: () -> Unit,
    private val logger: Logger,
    private val remindAfterMs: Long = 45 * MINUTE,
    private val pauseAfterNoAnswerMs: Long = 10 * MINUTE,
    private val maxRecordingMs: Long = 120 * MINUTE,
) {
    private val asking = MutableStateFlow(false)
    private val lowBattery = MutableStateFlow(false)

    /** Recorded time (pauses not counted), and the part of it since the doctor last answered. */
    private var recordedMs = 0L
    private var sinceAnswerMs = 0L
    private var askedAtMs = 0L
    private var ticker: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null

    val alert: StateFlow<RecordingAlert?> = combine(state, pauseReason, asking, lowBattery) { s, reason, ask, battery ->
        when {
            s == SessionState.PAUSED && reason == PauseReason.MIC_IN_USE -> RecordingAlert.MIC_IN_USE
            s == SessionState.PAUSED && reason == PauseReason.NO_RESPONSE -> RecordingAlert.PAUSED_NO_RESPONSE
            s == SessionState.RECORDING && ask -> RecordingAlert.STILL_RECORDING
            (s == SessionState.RECORDING || s == SessionState.PAUSED) && battery -> RecordingAlert.LOW_BATTERY
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    fun start() {
        scope.launch {
            var previous = SessionState.IDLE
            state.collect { s ->
                when (s) {
                    SessionState.STARTING -> reset()
                    SessionState.RECORDING -> {
                        // Back from a pause: the doctor is here, so the question is answered
                        if (previous == SessionState.PAUSED) answered()
                        startTicker()
                        watchBattery(true)
                    }
                    SessionState.PAUSED -> {
                        ticker?.cancel()
                        watchBattery(true)
                    }
                    else -> {
                        ticker?.cancel()
                        asking.value = false
                        watchBattery(false)
                    }
                }
                previous = s
            }
        }
    }

    /** "Continue" on the Still recording? question. */
    fun answered() {
        sinceAnswerMs = 0
        asking.value = false
    }

    private fun reset() {
        recordedMs = 0
        answered()
        lowBattery.value = false
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive && state.value == SessionState.RECORDING) {
                delay(TICK_MS.milliseconds)
                if (state.value != SessionState.RECORDING) break
                recordedMs += TICK_MS
                sinceAnswerMs += TICK_MS
                when {
                    recordedMs >= maxRecordingMs -> {
                        logger.warn(TAG, "Recording reached ${maxRecordingMs / MINUTE} min: ending it")
                        asking.value = false
                        runCatching { stop() }
                        break
                    }
                    asking.value && recordedMs - askedAtMs >= pauseAfterNoAnswerMs -> {
                        logger.warn(TAG, "No answer to Still recording?: pausing")
                        asking.value = false
                        runCatching { pause(PauseReason.NO_RESPONSE) }
                        break
                    }
                    !asking.value && sinceAnswerMs >= remindAfterMs -> {
                        askedAtMs = recordedMs
                        asking.value = true
                    }
                }
            }
        }
    }

    private fun watchBattery(on: Boolean) {
        if (on == (batteryReceiver != null)) return
        if (!on) {
            batteryReceiver?.let { runCatching { context.unregisterReceiver(it) } }
            batteryReceiver = null
            return
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onBattery(intent)
        }
        batteryReceiver = receiver
        // Sticky: the current level arrives at once
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let(::onBattery)
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return
        lowBattery.value = !charging && level * 100 / scale <= LOW_BATTERY_PERCENT
    }

    private companion object {
        const val TAG = "RecordingGuard"
        const val MINUTE = 60_000L
        const val TICK_MS = 1_000L
        const val LOW_BATTERY_PERCENT = 5
    }
}
