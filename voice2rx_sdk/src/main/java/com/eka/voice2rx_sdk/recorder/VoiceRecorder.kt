package com.eka.voice2rx_sdk.recorder

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
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import java.io.File
import java.io.IOException

class VoiceRecorder(
    val callback: AudioCallback,
    private val audioFocusListener: AudioFocusListener
) {

    companion object {
        const val TAG = "VoiceRecorder"
        var startTimestamp: Long = -1
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private var listeningState = ListeningState.LISTENING

    private var sampleRate: Int = 0
    private var frameSize: Int = 0

    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String? = null
    private var fullRecordingOutputFile: File? = null

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio focus change listener
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                VoiceLogger.d(TAG, "Microphone focus gone.")
                onMicrophoneFocusGone()
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                VoiceLogger.d(TAG, "Microphone focus gain.")
                onMicrophoneFocusGain()
            }
        }
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
                VoiceLogger.d(TAG, "Could not get audio focus")
            }
        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
            )
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                VoiceLogger.d(TAG, "Could not get audio focus")
            }
        }
    }

    private fun onMicrophoneFocusGone() {
        audioFocusListener.onAudioFocusGone()
        listeningState = ListeningState.PAUSE
    }

    private fun onMicrophoneFocusGain() {
        audioFocusListener.onAudioFocusGain()
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    fun stopRecording() {
        try {
            abandonAudioFocus()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start(context: Context, fullRecordingFile: File, sampleRate: Int, frameSize: Int) {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        stop()

        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            requestAudioFocus()
        } catch (e: IOException) {
            VoiceLogger.d(TAG, e.printStackTrace().toString())
            e.printStackTrace()
        }

        audioRecord = createAudioRecord()
        if (audioRecord != null) {
            listeningState = ListeningState.LISTENING
            audioRecord?.startRecording()
            startTimestamp = System.currentTimeMillis()
            thread = Thread(ProcessVoice())
            thread?.start()
        }
    }

    private fun createMediaRecorder(context: Context): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
    }

    fun pauseListening() {
        listeningState = ListeningState.PAUSE
        VoiceLogger.d(TAG, "Audio paused at: ${System.currentTimeMillis() - startTimestamp}ms")
    }

    fun resumeListening() {
        listeningState = ListeningState.LISTENING
        VoiceLogger.d(TAG, "Audio Resumed at: ${System.currentTimeMillis() - startTimestamp}ms")
    }

    fun stop() {
        listeningState = ListeningState.PAUSE
        thread?.interrupt()
        thread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        stopRecording()
        startTimestamp = -1
    }

    fun getFullRecordingOutputFile(): File? {
        return fullRecordingOutputFile
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        try {
            val minBufferSize = maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                2 * frameSize
            )

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufferSize
            )

            if (audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                return audioRecord
            } else {
                audioRecord.release()
            }
        } catch (e: IllegalArgumentException) {
            VoiceLogger.e(TAG, "Error can't create AudioRecord ")
        }
        return null
    }

    private inner class ProcessVoice : Runnable {
        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            val buffer = ShortArray(frameSize)
            while (!Thread.interrupted()) {
                if (listeningState == ListeningState.LISTENING) {

                    val read = audioRecord?.read(buffer, 0, buffer.size)
                    if (read != null && read > 0) {
                        val amplitude = getAudioAmplitude(read, buffer)
                        VoiceLogger.d(TAG, "onAudio")
                        callback.onAudio(
                            audioData = buffer.copyOfRange(0, read).copyOf(),
                            timeStamp = System.currentTimeMillis(),
                            amplitude = amplitude
                        )
                    }
                }
            }
        }
    }

    // This code do not take much time it takes 0 seconds
    // I measured it with measureTime function
    private fun getAudioAmplitude(read: Int, buffer: ShortArray): Float {
        if (read <= 0 || buffer.isEmpty()) return 0f

        val maxAmplitude = 32767f // Max value for 16-bit audio

        // Get the maximum absolute value from the buffer
        val maxValue = buffer.take(read).maxOfOrNull { kotlin.math.abs(it.toInt()) } ?: 0

        return (maxValue / maxAmplitude).coerceIn(0f, 1f)
    }
}

interface AudioCallback {
    fun onAudio(audioData: ShortArray, timeStamp: Long, amplitude: Float)
}