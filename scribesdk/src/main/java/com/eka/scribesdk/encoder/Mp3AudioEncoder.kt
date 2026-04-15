package com.eka.scribesdk.encoder

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes PCM audio frames to a raw MP3 stream (.mp3) using the LAME JNI wrapper.
 * No MP4/ISO-BMFF container — the output is a true audio-only MPEG Layer III bitstream.
 */
internal class Mp3AudioEncoder(private val logger: Logger) : AudioEncoder {

    companion object {
        private const val TAG = "Mp3AudioEncoder"
        private const val BIT_RATE_KBPS = 48
        private const val CHANNELS = 1
        private const val PCM_CHUNK_SAMPLES = 1152
    }

    override fun encode(
        frames: List<AudioFrame>,
        sampleRate: Int,
        outputPath: String
    ): EncodedChunk {
        val pcmData = combinePcm(frames)

        return try {
            val mp3Path = outputPath
                .replace(".mp4", ".mp3")
                .replace(".m4a", ".mp3")
                .replace(".aac", ".mp3")
                .replace(".wav", ".mp3")
                .let { if (!it.endsWith(".mp3")) "$it.mp3" else it }
            val mp3File = encodePcmToMp3(pcmData, sampleRate, mp3Path)
            EncodedChunk(
                filePath = mp3File.absolutePath,
                format = AudioFormat.MP3,
                sizeBytes = mp3File.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        } catch (e: Exception) {
            logger.error(TAG, "MP3 encoding failed, falling back to WAV", e)
            val wavPath = outputPath
                .replace(".mp3", ".wav")
                .replace(".mp4", ".wav")
                .replace(".m4a", ".wav")
                .replace(".aac", ".wav")
                .let { if (!it.endsWith(".wav")) "$it.wav" else it }
            val wavFile = writeWav(pcmData, sampleRate, wavPath)
            EncodedChunk(
                filePath = wavFile.absolutePath,
                format = AudioFormat.WAV,
                sizeBytes = wavFile.length(),
                durationMs = (pcmData.size * 1000L) / sampleRate
            )
        }
    }

    private fun encodePcmToMp3(pcm: ShortArray, sampleRate: Int, outputPath: String): File {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val lame = LameBuilder()
            .setInSampleRate(sampleRate)
            .setOutSampleRate(sampleRate)
            .setOutBitrate(BIT_RATE_KBPS)
            .setOutChannels(CHANNELS)
            .setMode(LameBuilder.Mode.MONO)
            .setQuality(5)
            .build()

        // LAME docs: mp3 buffer must hold at least 1.25 * samples + 7200 bytes.
        val mp3Buf = ByteArray((1.25 * PCM_CHUNK_SAMPLES + 7200).toInt())

        try {
            FileOutputStream(outputFile).use { out ->
                var offset = 0
                while (offset < pcm.size) {
                    val samples = minOf(PCM_CHUNK_SAMPLES, pcm.size - offset)
                    val chunk = if (offset == 0 && samples == pcm.size) {
                        pcm
                    } else {
                        pcm.copyOfRange(offset, offset + samples)
                    }
                    val encoded = lame.encode(chunk, chunk, samples, mp3Buf)
                    if (encoded < 0) {
                        throw IllegalStateException("LAME encode returned $encoded")
                    }
                    if (encoded > 0) out.write(mp3Buf, 0, encoded)
                    offset += samples
                }
                val flushed = lame.flush(mp3Buf)
                if (flushed > 0) out.write(mp3Buf, 0, flushed)
            }
        } finally {
            lame.close()
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
