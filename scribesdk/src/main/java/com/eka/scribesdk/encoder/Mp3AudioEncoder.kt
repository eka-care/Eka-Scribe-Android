package com.eka.scribesdk.encoder

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class Mp3AudioEncoder(private val logger: Logger) : AudioEncoder {

    companion object {
        private const val TAG = "Mp3AudioEncoder"
        private const val BIT_RATE = 48        // kbps, optimal for 16kHz speech
        private const val QUALITY = 7          // LAME quality (2=best, 7=fast, good enough)
        private const val CHUNK_SIZE = 8192    // Process 8192 samples at a time
    }

    override fun encode(
        frames: List<AudioFrame>,
        sampleRate: Int,
        outputPath: String
    ): EncodedChunk {
        val pcmData = combinePcm(frames)

        return try {
            val mp3File = encodePcmToMp3(pcmData, sampleRate, outputPath)
            EncodedChunk(
                filePath = mp3File.absolutePath,
                format = AudioFormat.MP3,
                sizeBytes = mp3File.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        } catch (e: Exception) {
            logger.error(TAG, "MP3 encoding failed, falling back to WAV", e)
            // Fallback: write WAV
            val wavPath = outputPath.replace(".mp3", ".wav")
            val wavFile = writeWav(pcmData, sampleRate, wavPath)
            EncodedChunk(
                filePath = wavFile.absolutePath,
                format = AudioFormat.WAV,
                sizeBytes = wavFile.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        }
    }

    private fun encodePcmToMp3(
        pcm: ShortArray,
        sampleRate: Int,
        outputPath: String
    ): File {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutChannels(1)          // mono
            .setOutBitrate(BIT_RATE)
            .setOutSampleRate(sampleRate)
            .setQuality(QUALITY)
            .build()

        try {
            // MP3 output buffer: worst case is 1.25 * samples + 7200
            val mp3BufSize = (1.25 * CHUNK_SIZE + 7200).toInt()
            val mp3Buf = ByteArray(mp3BufSize)

            FileOutputStream(outputFile).use { fos ->
                // Encode all PCM data in chunks to avoid oversized buffers
                var offset = 0
                while (offset < pcm.size) {
                    val remaining = pcm.size - offset
                    val count = minOf(CHUNK_SIZE, remaining)
                    val chunk = pcm.copyOfRange(offset, offset + count)

                    val bytesEncoded = lame.encode(
                        chunk,        // left channel (mono)
                        chunk,        // right channel (same for mono)
                        count,
                        mp3Buf
                    )

                    if (bytesEncoded > 0) {
                        fos.write(mp3Buf, 0, bytesEncoded)
                    }
                    offset += count
                }

                // Flush remaining MP3 frames
                val flushBytes = lame.flush(mp3Buf)
                if (flushBytes > 0) {
                    fos.write(mp3Buf, 0, flushBytes)
                }
            }
        } finally {
            lame.close()
        }

        return outputFile
    }

    // --- WAV fallback helpers (reused from M4aAudioEncoder) ---

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
}
