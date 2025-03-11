package com.eka.voice2rx_sdk.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import com.eka.voice2rx_sdk.common.VoiceLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class VoiceRecorder(val callback: AudioCallback) {

    companion object {
        const val TAG = "VoiceRecorder"
        var startTimestamp: Long = -1
    }

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null
    private var isListening = false

    private var sampleRate: Int = 0
    private var frameSize: Int = 0

    private var mediaRecorder: MediaRecorder? = null
    private var outputFilePath: String? = null
    private var fullRecordingOutputFile: File? = null

    fun stopRecording() {
        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun start(context: Context, fullRecordingFile: File, sampleRate: Int, frameSize: Int) {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        stop()

        try {

            mediaRecorder = createMediaRecorder(context).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(FileOutputStream(fullRecordingFile).fd)

                prepare()
                start()
            }
        } catch (e: IOException) {
            VoiceLogger.d("VoiceRecorder", e.printStackTrace().toString())
            e.printStackTrace()
        }

        audioRecord = createAudioRecord()
        if (audioRecord != null) {
            isListening = true
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

    fun stop() {
        isListening = false
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
                MediaRecorder.AudioSource.MIC,
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
            while (!Thread.interrupted() && isListening) {
                val read = audioRecord?.read(buffer, 0, buffer.size)
                if (read != null && read > 0) {
                    callback.onAudio(buffer.copyOfRange(0, read), System.currentTimeMillis())
                }
            }
        }
    }
}

interface AudioCallback {
    fun onAudio(audioData: ShortArray, timeStamp: Long)
}