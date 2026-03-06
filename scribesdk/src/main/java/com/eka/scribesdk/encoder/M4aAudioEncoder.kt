package com.eka.scribesdk.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import java.io.File
import java.nio.ByteBuffer

class M4aAudioEncoder(private val logger: Logger) : AudioEncoder {

    companion object {
        private const val TAG = "M4aAudioEncoder"
        private const val AAC_MIME = "audio/mp4a-latm"
        private const val BIT_RATE = 64000
        private const val TIMEOUT_US = 10000L
    }

    override fun encode(
        frames: List<AudioFrame>,
        sampleRate: Int,
        outputPath: String
    ): EncodedChunk {
        val pcmData = combinePcm(frames)
        val wavPath = outputPath.replace(".m4a", ".wav")
        val wavFile = writeWav(pcmData, sampleRate, wavPath)

        return try {
            val m4aFile = convertWavToM4a(wavFile, sampleRate, outputPath)
            wavFile.delete()

            EncodedChunk(
                filePath = m4aFile.absolutePath,
                format = AudioFormat.M4A,
                sizeBytes = m4aFile.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        } catch (e: Exception) {
            logger.error(TAG, "M4A encoding failed, falling back to WAV", e)
            EncodedChunk(
                filePath = wavFile.absolutePath,
                format = AudioFormat.WAV,
                sizeBytes = wavFile.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        }
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
        for (i in pcm.indices) {
            byteData[i * 2] = (pcm[i].toInt() and 0xFF).toByte()
            byteData[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xFF).toByte()
        }

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

    private fun convertWavToM4a(wavFile: File, sampleRate: Int, outputPath: String): File {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val format = MediaFormat.createAudioFormat(AAC_MIME, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }

        val codec = MediaCodec.createEncoderByType(AAC_MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val wavBytes = wavFile.readBytes()
        val pcmData = wavBytes.copyOfRange(44, wavBytes.size)
        var inputOffset = 0
        var presentationTimeUs = 0L
        var isEos = false

        val bufferInfo = MediaCodec.BufferInfo()

        while (!isEos) {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                val bytesToRead = minOf(inputBuffer.remaining(), pcmData.size - inputOffset)

                if (bytesToRead > 0) {
                    inputBuffer.put(pcmData, inputOffset, bytesToRead)
                    codec.queueInputBuffer(inputBufferIndex, 0, bytesToRead, presentationTimeUs, 0)
                    inputOffset += bytesToRead
                    presentationTimeUs = (inputOffset.toLong() * 1_000_000L) / (sampleRate * 2)
                } else {
                    codec.queueInputBuffer(
                        inputBufferIndex, 0, 0, presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                }
            }

            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }

                outputBufferIndex >= 0 -> {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex) ?: continue
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isEos = true
                    }
                }
            }
        }

        codec.stop()
        codec.release()
        if (muxerStarted) {
            muxer.stop()
            muxer.release()
        }

        return outputFile
    }
}
