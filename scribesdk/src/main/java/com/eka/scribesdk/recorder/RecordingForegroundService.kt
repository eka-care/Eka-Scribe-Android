package com.eka.scribesdk.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eka.scribesdk.R
import com.eka.scribesdk.api.EkaScribe
import com.eka.scribesdk.api.models.RecordingAlert
import com.eka.scribesdk.api.models.SessionState
import com.eka.scribesdk.common.logging.Logger

/**
 * Foreground service for a session, from the first recorded second until the note is done.
 * - Recording / paused (type microphone): Android cuts the mic off (pure silence) as soon as an app
 *   without one goes to the background or the screen locks.
 * - Uploading / processing (type dataSync): keeps the process alive so the upload and the
 *   "note ready" step are not killed with the app in the background.
 *
 * Driven only by the session state ([RecordingServiceController]); it stops by itself after.
 * Recording notification (design 7): Pause / Resume, and End, which opens the app on its own End
 * confirmation. On the lock screen a public copy shows only the state and Pause / Resume.
 */
internal class RecordingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    /** The foreground type we run as now; 0 while not in the foreground yet. */
    private var foregroundType = 0

    /**
     * Android 15+: a dataSync service gets about 6 hours a day. When that runs out Android calls
     * this and kills the app if the service is still running, so stop. The note keeps being
     * generated in the app; only the "keep alive in the background" guarantee is gone.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundType = 0
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A notification button: hand it to the SDK. The state change then updates or stops us.
        NotificationAction.entries.firstOrNull { it.intentAction == intent?.action }?.let { action ->
            onAction?.invoke(action)
            return START_NOT_STICKY
        }
        val phase = intent?.getStringExtra(EXTRA_PHASE)?.let { runCatching { Phase.valueOf(it) }.getOrNull() }
            ?: Phase.RECORDING
        val since = intent?.getLongExtra(EXTRA_SINCE, SystemClock.elapsedRealtime()) ?: SystemClock.elapsedRealtime()
        val label = intent?.getStringExtra(EXTRA_LABEL)
        val alert = intent?.getStringExtra(EXTRA_ALERT)?.let { runCatching { RecordingAlert.valueOf(it) }.getOrNull() }
        val alertNow = intent?.getBooleanExtra(EXTRA_ALERT_NEW, false) == true
        val notification = when (phase) {
            Phase.PROCESSING -> processingNotification(label)
            else -> recordingNotification(phase == Phase.PAUSED, since, alert, alertNow)
        }
        goForeground(notification, microphone = phase != Phase.PROCESSING)
        return START_NOT_STICKY
    }

    /**
     * Must succeed, or Android 12+ kills the app ("did not call startForeground"). If the
     * microphone type is refused (e.g. resumed while hidden), fall back to dataSync: the app keeps
     * running, only the mic is not guaranteed in the background.
     */
    private fun goForeground(notification: Notification, microphone: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (foregroundType != 0) return updateNotification(notification)
            runCatching { startForeground(NOTIFICATION_ID, notification) }
                .onSuccess { foregroundType = -1 }
                .onFailure { stopSelf() }
            return
        }
        val wanted = if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // Same type: only the text changed. Update it without asking Android to start us again,
        // which it can refuse while the app is in the background.
        if (foregroundType == wanted) return updateNotification(notification)
        val types = buildList {
            if (microphone) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        for (type in types) {
            if (runCatching { startForeground(NOTIFICATION_ID, notification, type) }.isSuccess) {
                foregroundType = type
                return
            }
        }
        // Refused (e.g. the daily dataSync time is used up). Already in the foreground: carry on
        // with the type we have. Not yet: stopping is only safe because the controller starts us
        // with startForegroundService just once, when the recording starts in the app.
        if (foregroundType != 0) updateNotification(notification) else stopSelf()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(notification: Notification) {
        runCatching { androidx.core.app.NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }

    private fun contentIntent(): PendingIntent? {
        val open = (openAppIntent ?: packageManager.getLaunchIntentForPackage(packageName)) ?: return null
        return PendingIntent.getActivity(
            this, 0, Intent(open).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** End opens the app with [EkaScribe.EXTRA_END_REQUESTED]; the app asks "End this recording?". */
    private fun endIntent(): PendingIntent? {
        val open = (openAppIntent ?: packageManager.getLaunchIntentForPackage(packageName)) ?: return null
        return PendingIntent.getActivity(
            this, 1,
            Intent(open)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EkaScribe.EXTRA_END_REQUESTED, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /**
     * The recording notification. With an [alert] it says what happened, and it rings once
     * ([alertNow]) on the alerts channel so it's noticed with the phone on the desk.
     */
    private fun recordingNotification(
        paused: Boolean,
        recordingSinceElapsed: Long,
        alert: RecordingAlert?,
        alertNow: Boolean,
    ): Notification {
        ensureChannel(this)
        // The chronometer runs on wall time; turn the elapsed-realtime start into a wall-clock base
        val whenMillis = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - recordingSinceElapsed)
        val title = when (alert) {
            RecordingAlert.MIC_IN_USE -> "Paused: mic in use by another app"
            RecordingAlert.STILL_RECORDING -> "Still recording?"
            RecordingAlert.PAUSED_NO_RESPONSE -> "Recording paused"
            RecordingAlert.LOW_BATTERY -> "Battery low"
            null -> if (paused) "Recording paused" else "Recording in progress"
        }
        val text = when (alert) {
            RecordingAlert.MIC_IN_USE -> "Nothing was being recorded. Resume when the other app is done."
            RecordingAlert.STILL_RECORDING -> "This visit has been recording for a long time. Continue or end it."
            RecordingAlert.PAUSED_NO_RESPONSE -> "Paused after no answer. Resume or end it."
            RecordingAlert.LOW_BATTERY -> "End the recording soon so the note isn't lost."
            null -> "Tap to open the session"
        }
        val firstAction = when {
            alert == RecordingAlert.STILL_RECORDING -> NotificationAction.CONTINUE
            paused -> NotificationAction.RESUME
            else -> NotificationAction.PAUSE
        }
        val pauseOrResume = NotificationCompat.Action.Builder(0, firstAction.label, actionIntent(firstAction)).build()

        val endAction = endIntent()?.let { end ->
            NotificationCompat.Action.Builder(0, "End", end).setAuthenticationRequired(true).build()
        }
        postAlert(alert, alertNow, title, text, pauseOrResume, endAction)

        fun base() = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scribe_recording)
            .setColor(SCRIBE_BLUE)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setUsesChronometer(!paused)
            .setShowWhen(!paused)
            .setWhen(whenMillis)
            .setContentIntent(contentIntent())

        val lockScreen = base()
            .setContentText(if (alert != null) text else "Patient details are hidden until you unlock.")
            .addAction(pauseOrResume)
            .build()
        return base()
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(lockScreen)
            .addAction(pauseOrResume)
            .apply { endAction?.let(::addAction) }
            .build()
    }

    /**
     * The ongoing notification is silent (and Android won't move it to another channel), so a
     * new alert also pops up once on its own, on the alerts channel. Cleared with the alert.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun postAlert(
        alert: RecordingAlert?,
        alertNow: Boolean,
        title: String,
        text: String,
        firstAction: NotificationCompat.Action,
        endAction: NotificationCompat.Action?,
    ) {
        val manager = androidx.core.app.NotificationManagerCompat.from(this)
        if (alert == null) {
            manager.cancel(ALERT_NOTIFICATION_ID)
            return
        }
        if (!alertNow) return
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scribe_recording)
            .setColor(SCRIBE_BLUE)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // no patient details in it
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .addAction(firstAction)
            .apply { endAction?.let(::addAction) }
            .build()
        runCatching { manager.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    /** Design 6 "Generating notes": patient · template; the lock screen copy hides them. */
    private fun processingNotification(label: String?): Notification {
        ensureChannel(this)
        fun base() = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_scribe_recording)
            .setColor(SCRIBE_BLUE)
            .setContentTitle("Generating notes")
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent())
        return base()
            .setContentText(label ?: "Tap to open the session")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(base().setContentText("Unlock to view progress.").build())
            .build()
    }

    private fun actionIntent(action: NotificationAction): PendingIntent = PendingIntent.getService(
        this,
        action.ordinal,
        Intent(this, RecordingForegroundService::class.java).setAction(action.intentAction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    internal enum class Phase { RECORDING, PAUSED, PROCESSING }

    /** Buttons on the recording notification that act right away (End goes through the app). */
    enum class NotificationAction(val intentAction: String, val label: String) {
        PAUSE("com.eka.scribesdk.recording.PAUSE", "Pause"),
        RESUME("com.eka.scribesdk.recording.RESUME", "Resume"),
        /** "Still recording?" answered yes. */
        CONTINUE("com.eka.scribesdk.recording.CONTINUE", "Continue"),
    }

    companion object {
        /** Set by [RecordingServiceController]; runs the button's action on the session. */
        @Volatile
        internal var onAction: ((NotificationAction) -> Unit)? = null

        /** Screen the notification opens; set by the app ([EkaScribe.setNotificationIntent]). */
        @Volatile
        internal var openAppIntent: Intent? = null

        private const val CHANNEL_ID = "ekascribe_recording"
        private const val ALERT_CHANNEL_ID = "ekascribe_recording_alerts"
        private const val EXTRA_ALERT = "alert"
        private const val EXTRA_ALERT_NEW = "alert_new"
        private const val SCRIBE_BLUE = 0xFF215FFF.toInt()
        private const val NOTIFICATION_ID = 0x5C1BE
        private const val ALERT_NOTIFICATION_ID = 0x5C1BD
        private const val EXTRA_PHASE = "phase"
        private const val EXTRA_SINCE = "recording_since_elapsed"
        private const val EXTRA_LABEL = "label"

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "EkaScribe recording", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while EkaScribe records a consultation and writes its note"
                    setShowBadge(false)
                }
            )
            if (manager.getNotificationChannel(ALERT_CHANNEL_ID) == null) manager.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL_ID, "EkaScribe recording alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Mic taken by another app, a long recording, or a low battery while recording"
                    setShowBadge(false)
                }
            )
        }

        internal fun show(
            context: Context,
            phase: Phase,
            recordingSinceElapsed: Long,
            label: String?,
            alert: RecordingAlert? = null,
            alertNow: Boolean = false,
            /** Already started: a plain start, which can't crash the app if Android declines it. */
            running: Boolean = false,
        ) {
            val intent = Intent(context, RecordingForegroundService::class.java)
                .putExtra(EXTRA_PHASE, phase.name)
                .putExtra(EXTRA_SINCE, recordingSinceElapsed)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_ALERT, alert?.name)
                .putExtra(EXTRA_ALERT_NEW, alertNow)
            if (!running) {
                ContextCompat.startForegroundService(context, intent)
                return
            }
            // A plain start of the running service is allowed from the background (the app has a
            // foreground service). startForegroundService would oblige it to call startForeground
            // again, and a refusal there crashes the app.
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                ContextCompat.startForegroundService(context, intent)
            }
        }

        internal fun hide(context: Context) {
            androidx.core.app.NotificationManagerCompat.from(context).cancel(ALERT_NOTIFICATION_ID)
            context.stopService(Intent(context, RecordingForegroundService::class.java))
        }
    }
}

/**
 * Starts / updates / stops [RecordingForegroundService] from the session state, and keeps the
 * notification's timer in step with the recording (paused time is not counted).
 */
internal class RecordingServiceController(
    private val context: Context,
    private val logger: Logger,
    /** "Rahul Rao · SOAP notes" for the session being processed, if known. */
    private val sessionLabel: () -> String?,
    /** Pause / Resume tapped on the notification. */
    onAction: (RecordingForegroundService.NotificationAction) -> Unit,
) {
    init {
        RecordingForegroundService.onAction = onAction
    }

    private var running = false
    private var state = SessionState.IDLE
    private var alert: RecordingAlert? = null
    /** Elapsed-realtime the recording would have started at, with paused time taken out. */
    private var recordingSince = 0L
    private var pausedAt = 0L

    /** An alert to show on the recording notification; rings once when it first appears. */
    fun onAlert(alert: RecordingAlert?) {
        if (alert == this.alert) return
        this.alert = alert
        if (running && (state == SessionState.RECORDING || state == SessionState.PAUSED)) runCatching {
            show(if (state == SessionState.PAUSED) RecordingForegroundService.Phase.PAUSED else RecordingForegroundService.Phase.RECORDING, alertNow = alert != null)
        }.onFailure { logger.warn(TAG, "Recording alert: ${it.message}") }
    }

    private fun show(phase: RecordingForegroundService.Phase, alertNow: Boolean = false, firstStart: Boolean = false) {
        RecordingForegroundService.show(context, phase, recordingSince, null, alert, alertNow, running = !firstStart)
    }

    fun onState(state: SessionState) {
        this.state = state
        val now = SystemClock.elapsedRealtime()
        try {
            when (state) {
                SessionState.RECORDING -> {
                    val firstStart = !running
                    when {
                        firstStart -> recordingSince = now
                        pausedAt != 0L -> recordingSince += now - pausedAt
                    }
                    pausedAt = 0L
                    running = true
                    show(RecordingForegroundService.Phase.RECORDING, firstStart = firstStart)
                }
                SessionState.PAUSED -> {
                    if (!running) return
                    if (pausedAt == 0L) pausedAt = now
                    show(RecordingForegroundService.Phase.PAUSED)
                }
                // Already in the foreground from recording: carry on through upload and processing
                SessionState.STOPPING, SessionState.PROCESSING -> if (running) {
                    RecordingForegroundService.show(
                        context, RecordingForegroundService.Phase.PROCESSING, recordingSince, sessionLabel(), running = true
                    )
                }
                else -> if (running) {
                    running = false
                    pausedAt = 0L
                    RecordingForegroundService.hide(context)
                }
            }
        } catch (e: Exception) {
            // Never let the notification break a recording (e.g. not allowed to start while hidden)
            logger.warn(TAG, "Recording service: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "RecordingService"
    }
}
