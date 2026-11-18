package com.eka.scribesdk.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.eka.scribesdk.common.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidAudioRecorder(
    private val context: Context,
    private val config: RecorderConfig,
    private val logger: Logger
) : AudioRecorder {

    companion object {
        private const val TAG = "AndroidAudioRecorder"

        /** Pure digital silence this long means another app holds the mic (backup for the system signal). */
        private const val SILENCE_AS_TAKEN_MS = 30_000L
        private const val SILENT_PEAK = 1
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var frameCallback: FrameCallback? = null
    private var focusCallback: AudioFocusCallback? = null
    private var micSilencedCallback: ((Boolean) -> Unit)? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    // Mic taken by another app: the system's "silenced" flag (Android 10+), or a long run of zeros
    private val silencedBySystem = AtomicBoolean(false)
    private val silencedByZeros = AtomicBoolean(false)
    private var reportedSilenced = false
    private var zeroRunMs = 0L
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logger.warn(TAG, "Audio focus lost: $focusChange")
                isPaused.set(true)
                focusCallback?.onFocusChanged(false)
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                logger.info(TAG, "Audio focus gained")
                focusCallback?.onFocusChanged(true)
            }
        }
    }

    override fun setFrameCallback(callback: FrameCallback) {
        this.frameCallback = callback
    }

    override fun setAudioFocusCallback(callback: AudioFocusCallback) {
        this.focusCallback = callback
    }

    override fun setMicSilencedCallback(callback: (Boolean) -> Unit) {
        this.micSilencedCallback = callback
    }

    @Synchronized
    private fun reportSilenced() {
        val silenced = silencedBySystem.get() || silencedByZeros.get()
        if (silenced == reportedSilenced) return
        reportedSilenced = silenced
        logger.warn(TAG, if (silenced) "Mic taken by another app: recording silence" else "Mic is back")
        micSilencedCallback?.invoke(silenced)
    }

    /** Android 10+: the system tells us when another app takes the mic and we are silenced. */
    private fun watchSilencing() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<android.media.AudioRecordingConfiguration>) {
                val sessionId = audioRecord?.audioSessionId ?: return
                val mine = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
                silencedBySystem.set(mine.isClientSilenced)
                reportSilenced()
            }
        }
        recordingCallback = callback
        audioManager?.registerAudioRecordingCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    /** Backup on every Android version: exact zeros for [SILENCE_AS_TAKEN_MS]. A real room is never this quiet. */
    private fun trackZeros(pcm: ShortArray, count: Int) {
        var peak = 0
        for (i in 0 until count) {
            val v = kotlin.math.abs(pcm[i].toInt())
            if (v > peak) peak = v
            if (peak > SILENT_PEAK) break
        }
        if (peak > SILENT_PEAK) {
            zeroRunMs = 0
            if (silencedByZeros.getAndSet(false)) reportSilenced()
            return
        }
        zeroRunMs += count * 1000L / config.sampleRate
        if (zeroRunMs >= SILENCE_AS_TAKEN_MS && !silencedByZeros.getAndSet(true)) reportSilenced()
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRecording.get()) {
            logger.warn(TAG, "Already recording")
            return
        }

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        requestAudioFocus()

        val channelConfig = if (config.channels == 1)
            AudioFormat.CHANNEL_IN_MONO
        else
            AudioFormat.CHANNEL_IN_STEREO

        val bufferSize = AudioRecord.getMinBufferSize(
            config.sampleRate,
            channelConfig,
            config.encoding
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            config.sampleRate,
            channelConfig,
            config.encoding,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            logger.error(TAG, "AudioRecord failed to initialize")
            audioRecord?.release()
            audioRecord = null
            abandonAudioFocus()
            return
        }

        isRecording.set(true)
        isPaused.set(false)
        frameCounter.set(0)
        zeroRunMs = 0
        silencedBySystem.set(false)
        silencedByZeros.set(false)
        reportedSilenced = false
        audioRecord?.startRecording()
        watchSilencing()

        recordThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val buffer = ShortArray(config.frameSize)

            while (isRecording.get()) {
                if (isPaused.get()) {
                    Thread.sleep(10)
                    continue
                }

                val readCount = audioRecord?.read(buffer, 0, config.frameSize) ?: -1
                if (readCount > 0) {
                    trackZeros(buffer, readCount)
                    val frame = AudioFrame(
                        pcm = buffer.copyOf(readCount),
                        timestampMs = System.currentTimeMillis(),
                        sampleRate = config.sampleRate,
                        frameIndex = frameCounter.getAndIncrement()
                    )
                    frameCallback?.onFrame(frame)
                }
            }
        }, "ScribeAudioRecorder")

        recordThread?.start()
        logger.info(TAG, "Recording started: ${config.sampleRate}Hz, frame=${config.frameSize}")
    }

    override fun stop() {
        isRecording.set(false)
        audioRecord?.stop()
        recordThread?.join()
        recordThread = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            recordingCallback?.let { audioManager?.unregisterAudioRecordingCallback(it) }
        }
        recordingCallback = null
        audioRecord?.release()
        audioRecord = null
        abandonAudioFocus()
        logger.info(TAG, "Recording stopped")
    }

    override fun pause() {
        isPaused.set(true)
        logger.info(TAG, "Recording paused")
    }

    override fun resume() {
        // Count silence afresh: the zeros before the pause shouldn't pause us again straight away
        zeroRunMs = 0
        if (silencedByZeros.getAndSet(false)) reportSilenced()
        isPaused.set(false)
        logger.info(TAG, "Recording resumed")
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest =
                AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()

            val result = audioManager?.requestAudioFocus(audioFocusRequest!!)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                logger.warn(TAG, "Could not get audio focus")
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                logger.warn(TAG, "Could not get audio focus")
            }
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (e: Exception) {
            logger.warn(TAG, "Error abandoning audio focus", e)
        }
        audioManager = null
        audioFocusRequest = null
    }
}
