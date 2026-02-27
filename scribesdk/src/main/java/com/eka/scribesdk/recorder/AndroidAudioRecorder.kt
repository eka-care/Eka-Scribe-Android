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
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var frameCallback: FrameCallback? = null
    private var focusCallback: AudioFocusCallback? = null
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
        audioRecord?.startRecording()

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
