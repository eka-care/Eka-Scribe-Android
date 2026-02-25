package com.eka.scribesdk.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.eka.scribesdk.common.logging.Logger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidAudioRecorder(
    private val config: RecorderConfig,
    private val logger: Logger
) : AudioRecorder {

    companion object {
        private const val TAG = "AndroidAudioRecorder"
    }

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private var callback: FrameCallback? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)

    override fun setFrameCallback(callback: FrameCallback) {
        this.callback = callback
    }

    @SuppressLint("MissingPermission")
    override fun start() {
        if (isRecording.get()) {
            logger.warn(TAG, "Already recording")
            return
        }

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
                    callback?.onFrame(frame)
                }
            }
        }, "ScribeAudioRecorder")

        recordThread?.start()
        logger.info(TAG, "Recording started: ${config.sampleRate}Hz, frame=${config.frameSize}")
    }

    override fun stop() {
        isRecording.set(false)
        recordThread?.join(1000)
        recordThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
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
}
