package com.eka.scribesdk.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes PCM audio frames to AAC in MP4 container (.mp4) using MediaCodec + MediaMuxer.
 */
internal class AacAudioEncoder(private val logger: Logger) : AudioEncoder {

    companion object {
        private const val TAG = "AacAudioEncoder"
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val BIT_RATE = 48000       // 48 kbps
        private const val CHANNELS = 1           // mono
        private const val TIMEOUT_US = 10_000L
    }

    override fun encode(
        frames: List<AudioFrame>,
        sampleRate: Int,
        outputPath: String
    ): EncodedChunk {
        val pcmData = combinePcm(frames)

        return try {
            val mp4Path = outputPath
                .replace(".mp3", ".mp4")
                .replace(".m4a", ".mp4")
                .replace(".aac", ".mp4")
                .let { if (!it.endsWith(".mp4")) "$it.mp4" else it }
            val mp4File = encodePcmToMp4(pcmData, sampleRate, mp4Path)
            EncodedChunk(
                filePath = mp4File.absolutePath,
                format = AudioFormat.MP4,
                sizeBytes = mp4File.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        } catch (e: Exception) {
            logger.error(TAG, "AAC encoding failed, falling back to WAV", e)
            val wavPath = outputPath
                .replace(".mp3", ".wav")
                .replace(".m4a", ".wav")
                .replace(".aac", ".wav")
                .replace(".mp4", ".wav")
            val wavFile = writeWav(pcmData, sampleRate, wavPath)
            EncodedChunk(
                filePath = wavFile.absolutePath,
                format = AudioFormat.WAV,
                sizeBytes = wavFile.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        }
    }

    private fun encodePcmToMp4(pcm: ShortArray, sampleRate: Int, outputPath: String): File {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val pcmBytes = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)

        val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, CHANNELS).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, pcmBytes.size)
        }

        val codec = MediaCodec.createEncoderByType(MIME_TYPE)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val bufferInfo = MediaCodec.BufferInfo()
        var inputOffset = 0
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        inputBuffer.clear()

                        val remaining = pcmBytes.size - inputOffset
                        if (remaining <= 0) {
                            codec.queueInputBuffer(
                                inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val size = minOf(remaining, inputBuffer.capacity())
                            inputBuffer.put(pcmBytes, inputOffset, size)
                            val presentationTimeUs = (inputOffset / 2) * 1_000_000L / sampleRate
                            codec.queueInputBuffer(inputIndex, 0, size, presentationTimeUs, 0)
                            inputOffset += size
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    outputIndex >= 0 -> {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: continue
                        if (bufferInfo.size > 0 && muxerStarted) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }
        }

        return outputFile
    }

    private fun combinePcm(frames: List<AudioFrame>): ShortArray {
        val totalSamples = frames.sumOf { it.pcm.size }
        val result = ShortArray(totalSamples)
        var offset = 0
        for (frame in frames) {
            frame.pcm.copyInto(result, offset)
            offset += frame.pcm.size
        }
        return result
    }

    private fun writeWav(pcm: ShortArray, sampleRate: Int, path: String): File {
        val file = File(path)
        file.parentFile?.mkdirs()

        val byteData = ByteArray(pcm.size * 2)
        ByteBuffer.wrap(byteData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm)

        val header = createWavHeader(byteData.size, sampleRate)
        file.outputStream().use { out ->
            out.write(header)
            out.write(byteData)
        }
        return file
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = 36 + dataSize

        return ByteBuffer.allocate(44).apply {
            put("RIFF".toByteArray())
            putInt(Integer.reverseBytes(totalSize))
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(Integer.reverseBytes(16))
            putShort(java.lang.Short.reverseBytes(1))
            putShort(java.lang.Short.reverseBytes(channels.toShort()))
            putInt(Integer.reverseBytes(sampleRate))
            putInt(Integer.reverseBytes(byteRate))
            putShort(java.lang.Short.reverseBytes(blockAlign.toShort()))
            putShort(java.lang.Short.reverseBytes(bitsPerSample.toShort()))
            put("data".toByteArray())
            putInt(Integer.reverseBytes(dataSize))
        }.array()
    }
}
