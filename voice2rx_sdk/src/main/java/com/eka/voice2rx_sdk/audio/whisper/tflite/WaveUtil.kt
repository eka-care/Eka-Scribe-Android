package com.eka.voice2rx_sdk.audio.whisper.tflite

import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Utility class for WAV file operations.
 */
object WaveUtil {

    private const val TAG = "WaveUtil"
    const val RECORDING_FILE = "MicInput.wav"

    /**
     * Create a WAV file from audio samples.
     *
     * @param filePath Output file path
     * @param samples Audio samples as byte array
     * @param sampleRate Sample rate (e.g., 16000)
     * @param numChannels Number of channels (1 for mono)
     * @param bytesPerSample Bytes per sample (2 for 16-bit, 4 for 32-bit float)
     */
    fun createWaveFile(
        filePath: String,
        samples: ByteArray,
        sampleRate: Int,
        numChannels: Int,
        bytesPerSample: Int
    ) {
        try {
            val dataSize = samples.size
            val audioFormat = when (bytesPerSample) {
                2 -> 1  // PCM_16
                4 -> 3  // PCM_FLOAT
                else -> 0
            }

            FileOutputStream(filePath).use { fos ->
                fos.write("RIFF".toByteArray(StandardCharsets.UTF_8))
                fos.write(intToByteArray(36 + dataSize), 0, 4)
                fos.write("WAVE".toByteArray(StandardCharsets.UTF_8))
                fos.write("fmt ".toByteArray(StandardCharsets.UTF_8))
                fos.write(intToByteArray(16), 0, 4)
                fos.write(shortToByteArray(audioFormat.toShort()), 0, 2)
                fos.write(shortToByteArray(numChannels.toShort()), 0, 2)
                fos.write(intToByteArray(sampleRate), 0, 4)
                fos.write(intToByteArray(sampleRate * numChannels * bytesPerSample), 0, 4)
                fos.write(shortToByteArray((numChannels * bytesPerSample).toShort()), 0, 2)
                fos.write(shortToByteArray((bytesPerSample * 8).toShort()), 0, 2)
                fos.write("data".toByteArray(StandardCharsets.UTF_8))
                fos.write(intToByteArray(dataSize), 0, 4)
                fos.write(samples)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error creating WAV file", e)
        }
    }

    /**
     * Read audio samples from a WAV file.
     *
     * @param filePath Path to the WAV file
     * @return Float array of audio samples normalized to [-1, 1]
     */
    fun getSamples(filePath: String): FloatArray {
        return try {
            FileInputStream(filePath).use { fis ->
                // Read WAV header
                val header = ByteArray(44)
                fis.read(header)

                // Validate RIFF header
                val headerStr = String(header, 0, 4)
                if (headerStr != "RIFF") {
                    Log.e(TAG, "Not a valid WAV file")
                    return floatArrayOf()
                }

                // Get audio format details
                val bitsPerSample = byteArrayToNumber(header, 34, 2)
                if (bitsPerSample != 16 && bitsPerSample != 32) {
                    Log.e(TAG, "Unsupported bits per sample: $bitsPerSample")
                    return floatArrayOf()
                }

                // Read audio data
                val dataLength = fis.available()
                val bytesPerSample = bitsPerSample / 8
                val numSamples = dataLength / bytesPerSample

                val audioData = ByteArray(dataLength)
                fis.read(audioData)

                val byteBuffer = ByteBuffer.wrap(audioData)
                byteBuffer.order(ByteOrder.nativeOrder())

                // Convert to float samples
                FloatArray(numSamples) { i ->
                    if (bitsPerSample == 16) {
                        byteBuffer.getShort(i * 2) / 32768f
                    } else {
                        byteBuffer.getFloat(i * 4)
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error reading WAV file", e)
            floatArrayOf()
        }
    }

    private fun byteArrayToNumber(bytes: ByteArray, offset: Int, length: Int): Int {
        var value = 0
        for (i in 0 until length) {
            value = value or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        }
        return value
    }

    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}
