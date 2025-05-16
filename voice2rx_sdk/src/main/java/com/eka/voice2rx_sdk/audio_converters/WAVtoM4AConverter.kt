package com.eka.voice2rx_sdk.audio_converters

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.eka.voice2rx_sdk.common.voicelogger.VoiceLogger
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

class WAVtoM4AConverter(
    private val sampleRate: Int,
    private val numberOfChannels: Int,
    private val bitRate: Int
) {
    private val OUTPUT_AUDIO_FILE_MIME_TYPE = "audio/mp4a-latm"
    private val CODEC_TIMEOUT_IN_MS = 5000L
    private val BUFFER_SIZE = sampleRate

    var wavFileHeaderSize = 44L

    private val tempBuffer = ByteArray(BUFFER_SIZE)

    fun convert(
        inputWavFile: File,
        destinationM4aFile: File,
        onResult: (ConversionResult) -> Unit
    ) {
        VoiceLogger.d("WavToM4AConverter", "Start conversion from WAV to M4A")
        val muxer = MediaMuxer(
            destinationM4aFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        val wavFileInputStream = FileInputStream(inputWavFile)
        wavFileInputStream.skip(wavFileHeaderSize)

        val codec = createMediaCodec()
        val outputBuffInfo = MediaCodec.BufferInfo()
        val inputQueueProgress = InputQueueProgress()

        try {
            while (outputBuffInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                queueInputBuffer(wavFileInputStream, codec, inputQueueProgress)
                dequeueOutputBuffer(muxer, codec, outputBuffInfo)
            }
        } catch (error: Error) {
            VoiceLogger.e(
                "WavToM4AConverter",
                "Error while converting WAV to M4A. Error message: ${error.message}"
            )
            onResult.invoke(ConversionResult(error.message))
        } finally {
            wavFileInputStream.close()
            muxer.stop()
            muxer.release()
        }

        onResult.invoke(ConversionResult())
    }

    private fun queueInputBuffer(
        wavFileInputStream: FileInputStream,
        codec: MediaCodec,
        inputQueueProgress: InputQueueProgress
    ) {
        var hasMoreData = true

        var inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
        while (inputBufferIndex != -1 && hasMoreData) {
            val destinationBuffer: ByteBuffer = codec.getInputBuffer(inputBufferIndex)!!
            destinationBuffer.clear()

            val bytesRead: Int = wavFileInputStream.read(tempBuffer, 0, destinationBuffer.limit())

            if (bytesRead == -1) {
                hasMoreData = false
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    0,
                    inputQueueProgress.presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            } else {
                destinationBuffer.put(tempBuffer, 0, bytesRead)
                codec.queueInputBuffer(
                    inputBufferIndex,
                    0,
                    bytesRead,
                    inputQueueProgress.presentationTimeUs,
                    0
                )

                inputQueueProgress.presentationTimeUs += 1000000L * (bytesRead / 4) / sampleRate
            }

            inputBufferIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_IN_MS)
        }
    }

    private fun dequeueOutputBuffer(
        muxer: MediaMuxer,
        codec: MediaCodec,
        outputBuffInfo: MediaCodec.BufferInfo
    ) {
        var audioTrackId = 0

        var outputBufferIndex = codec.dequeueOutputBuffer(outputBuffInfo, CODEC_TIMEOUT_IN_MS)
        while (outputBufferIndex != -1) {
            if (outputBufferIndex >= 0) {
                val encodedData = codec.getOutputBuffer(outputBufferIndex)!!
                encodedData.position(outputBuffInfo.offset)
                encodedData.limit(outputBuffInfo.offset + outputBuffInfo.size)

                muxer.writeSampleData(audioTrackId, encodedData, outputBuffInfo)
                codec.releaseOutputBuffer(outputBufferIndex, false);
            } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val outputFormat = codec.outputFormat
                audioTrackId = muxer.addTrack(outputFormat)
                muxer.start()
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                throw Error("WavToM4AConverter. INFO_TRY_AGAIN_LATER")
            } else {
                VoiceLogger.d(
                    "WavToM4AConverter",
                    "Unknown return code from dequeueOutputBuffer - " + outputBufferIndex
                )
            }

            outputBufferIndex = codec.dequeueOutputBuffer(outputBuffInfo, CODEC_TIMEOUT_IN_MS)
        }
    }

    private fun createMediaCodec(): MediaCodec {
        val outputFormat =
            MediaFormat.createAudioFormat(OUTPUT_AUDIO_FILE_MIME_TYPE, sampleRate, numberOfChannels)
        outputFormat.setInteger(
            MediaFormat.KEY_AAC_PROFILE,
            MediaCodecInfo.CodecProfileLevel.AACObjectLC
        )
        outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate)

        val codec = MediaCodec.createEncoderByType(OUTPUT_AUDIO_FILE_MIME_TYPE)
        codec.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        return codec
    }
}