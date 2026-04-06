package com.eka.scribesdk.encoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes a pre-recorded audio file to PCM, splits it into fixed-duration chunks,
 * and re-encodes each chunk to MP3 using the existing AudioEncoder.
 */
internal class AudioFileChunker(
    private val encoder: AudioEncoder,
    private val outputDir: File,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "AudioFileChunker"
        private const val TARGET_SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
        private const val TIMEOUT_US = 10_000L
    }

    data class ChunkResult(
        val filePath: String,
        val fileName: String,
        val index: Int,
        val startTimeMs: Long,
        val endTimeMs: Long,
        val durationMs: Long
    )

    fun chunkAudioFile(
        sourceFile: File,
        sessionId: String,
        chunkDurationSec: Int = 25
    ): List<ChunkResult> {
        logger.info(TAG, "Chunking file: ${sourceFile.name}, chunkDuration=${chunkDurationSec}s")

        // 1. Decode entire file to PCM
        val pcmData = decodeToMonoPcm16k(sourceFile)
        if (pcmData.isEmpty()) {
            logger.error(TAG, "Failed to decode audio file or file is empty")
            return emptyList()
        }

        logger.info(TAG, "Decoded ${pcmData.size} samples (${pcmData.size / TARGET_SAMPLE_RATE}s)")

        // 2. Split into chunks and encode each
        val samplesPerChunk = chunkDurationSec * TARGET_SAMPLE_RATE
        val results = mutableListOf<ChunkResult>()
        var offset = 0
        var chunkIndex = 0

        while (offset < pcmData.size) {
            val end = minOf(offset + samplesPerChunk, pcmData.size)
            val chunkPcm = pcmData.copyOfRange(offset, end)
            val chunkSamples = chunkPcm.size

            // Wrap PCM in AudioFrame list for the encoder
            val frames = mutableListOf<AudioFrame>()
            var frameOffset = 0
            var frameIdx = 0L
            while (frameOffset < chunkSamples) {
                val frameEnd = minOf(frameOffset + FRAME_SIZE, chunkSamples)
                val framePcm = chunkPcm.copyOfRange(frameOffset, frameEnd)
                frames.add(
                    AudioFrame(
                        pcm = framePcm,
                        timestampMs = (offset + frameOffset) * 1000L / TARGET_SAMPLE_RATE,
                        sampleRate = TARGET_SAMPLE_RATE,
                        frameIndex = frameIdx++
                    )
                )
                frameOffset = frameEnd
            }

            // 1-based file naming to match real-time pipeline
            val fileIndex = chunkIndex + 1
            val outputPath = File(outputDir, "${sessionId}_${fileIndex}.mp3").absolutePath
            val encoded = encoder.encode(frames, TARGET_SAMPLE_RATE, outputPath)

            val startTimeMs = offset.toLong() * 1000 / TARGET_SAMPLE_RATE
            val endTimeMs = end.toLong() * 1000 / TARGET_SAMPLE_RATE

            results.add(
                ChunkResult(
                    filePath = encoded.filePath,
                    fileName = "${fileIndex}.${encoded.format.extension}",
                    index = chunkIndex,
                    startTimeMs = startTimeMs,
                    endTimeMs = endTimeMs,
                    durationMs = endTimeMs - startTimeMs
                )
            )

            logger.info(
                TAG,
                "Chunk #$fileIndex: ${startTimeMs}ms-${endTimeMs}ms, file=${encoded.filePath}"
            )
            offset = end
            chunkIndex++
        }

        logger.info(TAG, "Created ${results.size} chunks from ${sourceFile.name}")
        return results
    }

    /**
     * Decodes any audio file to mono 16-bit PCM at 16kHz.
     * Uses MediaExtractor + MediaCodec for format-agnostic decoding.
     */
    private fun decodeToMonoPcm16k(file: File): ShortArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: Exception) {
            logger.error(TAG, "Failed to set data source: ${file.absolutePath}", e)
            return ShortArray(0)
        }

        // Find audio track
        var audioTrackIndex = -1
        var sourceFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                sourceFormat = format
                break
            }
        }

        if (audioTrackIndex == -1 || sourceFormat == null) {
            logger.error(TAG, "No audio track found in file")
            extractor.release()
            return ShortArray(0)
        }

        extractor.selectTrack(audioTrackIndex)

        val sourceSampleRate = sourceFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val sourceChannels = sourceFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = sourceFormat.getString(MediaFormat.KEY_MIME)!!

        logger.info(TAG, "Source: $mime, ${sourceSampleRate}Hz, ${sourceChannels}ch")

        // Create decoder
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(sourceFormat, null, null, 0)
        codec.start()

        val allPcm = mutableListOf<Short>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex,
                            0,
                            0,
                            0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            // Drain output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }

                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                outputBuffer.order(ByteOrder.nativeOrder())

                // Read decoded PCM samples
                val shortBuffer = outputBuffer.asShortBuffer()
                val samples = ShortArray(shortBuffer.remaining())
                shortBuffer.get(samples)

                // Convert to mono if stereo
                val monoSamples = if (sourceChannels > 1) {
                    toMono(samples, sourceChannels)
                } else {
                    samples
                }

                // Resample to 16kHz if needed
                val resampledSamples = if (sourceSampleRate != TARGET_SAMPLE_RATE) {
                    resample(monoSamples, sourceSampleRate, TARGET_SAMPLE_RATE)
                } else {
                    monoSamples
                }

                for (s in resampledSamples) {
                    allPcm.add(s)
                }

                codec.releaseOutputBuffer(outputIndex, false)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        return allPcm.toShortArray()
    }

    private fun toMono(samples: ShortArray, channels: Int): ShortArray {
        val monoCount = samples.size / channels
        val mono = ShortArray(monoCount)
        for (i in 0 until monoCount) {
            var sum = 0L
            for (ch in 0 until channels) {
                sum += samples[i * channels + ch]
            }
            mono[i] =
                (sum / channels).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
        }
        return mono
    }

    private fun resample(input: ShortArray, inputRate: Int, outputRate: Int): ShortArray {
        if (inputRate == outputRate) return input
        val ratio = inputRate.toDouble() / outputRate
        val outputLength = (input.size / ratio).toInt()
        val output = ShortArray(outputLength)
        for (i in 0 until outputLength) {
            val srcPos = i * ratio
            val srcIndex = srcPos.toInt()
            val frac = srcPos - srcIndex
            if (srcIndex + 1 < input.size) {
                // Linear interpolation
                val sample = input[srcIndex] * (1.0 - frac) + input[srcIndex + 1] * frac
                output[i] =
                    sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
            } else if (srcIndex < input.size) {
                output[i] = input[srcIndex]
            }
        }
        return output
    }
}
