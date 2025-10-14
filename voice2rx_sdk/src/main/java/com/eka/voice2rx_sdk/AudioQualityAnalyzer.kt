package com.eka.voice2rx_sdk

import com.eka.voice2rx_sdk.common.models.AudioQualityMetrics
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

internal class AudioQualityAnalyzer {

    companion object {
        private const val MAX_AMPLITUDE_16BIT = 32767f
        private const val CLIPPING_THRESHOLD = 32760 // Near max for 16-bit
        private const val NOISE_WINDOW_SIZE = 50
        private const val SILENCE_THRESHOLD = 0.01f
    }

    private var noiseFloor = 0f
    private val noiseWindow = ArrayDeque<Float>(NOISE_WINDOW_SIZE)

    /**
     * Analyzes audio frame and returns quality metrics
     */
    fun analyzeFrame(buffer: ShortArray, read: Int): AudioQualityMetrics {
        val rms = calculateRMS(buffer, read)
        val peak = calculatePeak(buffer, read)
        val zcr = calculateZeroCrossingRate(buffer, read)
        val clipping = detectClipping(buffer, read)
        val snr = calculateAudioQuality(buffer = buffer, size = read)

        return AudioQualityMetrics(
            rmsLevel = rms,
            peakLevel = peak,
            zeroCrossingRate = zcr,
            clippingDetected = clipping,
            signalToNoiseRatio = snr.toFloat()
        )
    }

    /**
     * Calculate Root Mean Square (RMS) level
     * Represents average signal power
     */
    private fun calculateRMS(buffer: ShortArray, read: Int): Float {
        if (read <= 0 || buffer.isEmpty()) return 0f

        var sum = 0.0
        for (i in 0 until read) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }

        return (sqrt(sum / read) / MAX_AMPLITUDE_16BIT).toFloat()
    }

    /**
     * Calculate peak level
     * Represents maximum amplitude in the frame
     */
    private fun calculatePeak(buffer: ShortArray, read: Int): Float {
        if (read <= 0 || buffer.isEmpty()) return 0f

        var maxValue = 0
        for (i in 0 until read) {
            val absValue = abs(buffer[i].toInt())
            if (absValue > maxValue) {
                maxValue = absValue
            }
        }

        return maxValue / MAX_AMPLITUDE_16BIT
    }

    /**
     * Calculate Zero Crossing Rate
     * Useful for distinguishing speech from noise
     */
    private fun calculateZeroCrossingRate(buffer: ShortArray, read: Int): Float {
        if (read <= 1 || buffer.isEmpty()) return 0f

        var crossings = 0
        for (i in 1 until read) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) ||
                (buffer[i] < 0 && buffer[i - 1] >= 0)
            ) {
                crossings++
            }
        }

        return crossings.toFloat() / read
    }

    /**
     * Detect audio clipping (distortion)
     */
    private fun detectClipping(buffer: ShortArray, read: Int): Boolean {
        if (read <= 0 || buffer.isEmpty()) return false

        for (i in 0 until read) {
            if (abs(buffer[i].toInt()) > CLIPPING_THRESHOLD) {
                return true
            }
        }

        return false
    }

    /**
     * Calculate Signal-to-Noise Ratio (SNR)
     * Higher values indicate better quality
     */
    private fun calculateSNR(rms: Float): Float {
        updateNoiseFloor(rms)

        return if (noiseFloor > 0f && rms > noiseFloor) {
            20 * log10(rms / noiseFloor)
        } else {
            0f
        }
    }


    fun calculateAudioQuality(buffer: ShortArray, size: Int): Int {
        val snr = calculateSNR(buffer, size)

        // Convert SNR to quality percentage
        // SNR > 40 dB is excellent (100%)
        // SNR < 0 dB is very poor (0%)
        val quality = when {
            snr >= 40 -> 100
            snr <= 0 -> 0
            else -> ((snr / 40.0) * 100).toInt()
        }

        return quality.coerceIn(0, 100)
    }

    private fun calculateSNR(buffer: ShortArray, size: Int): Double {
        // Calculate RMS (Root Mean Square) of the signal
        var sumSquares = 0.0
        for (i in 0 until size) {
            sumSquares += buffer[i] * buffer[i]
        }
        val rms = sqrt(sumSquares / size)

        // Estimate noise floor using lower amplitude samples
        val sortedBuffer = buffer.take(size).map { abs(it.toInt()) }.sorted()
        val noiseFloorIndex = (size * 0.1).toInt() // Use bottom 10% as noise estimate
        val noiseFloor = sortedBuffer.take(noiseFloorIndex).average()

        // Calculate SNR in dB
        val snr = if (noiseFloor > 0) {
            20 * log10(rms / noiseFloor)
        } else {
            40.0 // Default to high SNR if no noise detected
        }

        return snr.coerceIn(-10.0, 60.0)
    }

    /**
     * Update noise floor estimation using a sliding window
     * Noise floor is estimated from silent/low-level frames
     */
    private fun updateNoiseFloor(rms: Float) {
        // Only update noise floor during silence
        if (rms < SILENCE_THRESHOLD) {
            if (noiseWindow.size >= NOISE_WINDOW_SIZE) {
                noiseWindow.removeFirst()
            }
            noiseWindow.addLast(rms)

            // Calculate average of noise window
            if (noiseWindow.isNotEmpty()) {
                noiseFloor = noiseWindow.average().toFloat()
            }
        }
    }

    /**
     * Reset the analyzer state
     */
    fun reset() {
        noiseFloor = 0f
        noiseWindow.clear()
    }
}