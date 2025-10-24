package com.eka.voice2rx_sdk.common

import com.eka.voice2rx_sdk.audio.processing.AudioProcessor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

object AudioQualityAnalyzer {

    /**
     * Calculate SNR using Root Mean Square (RMS) method
     */
    private fun calculateSNRFromSamples(samples: DoubleArray): Double? {
        if (samples.isEmpty()) return null

        // Calculate signal power (RMS of entire signal)
        val signalRMS = calculateRMS(samples)

        // Estimate noise floor (using quietest 10% of samples)
        val noiseRMS = estimateNoiseFloor(samples)

        // Avoid division by zero
        if (noiseRMS == 0.0 || signalRMS == 0.0) return null

        // SNR in dB = 20 * log10(signal_RMS / noise_RMS)
        return 20 * log10(signalRMS / noiseRMS)
    }

    /**
     * Calculate Root Mean Square (RMS) of audio samples
     */
    private fun calculateRMS(samples: DoubleArray): Double {
        val sumSquares = samples.fold(0.0) { acc, sample ->
            acc + sample.pow(2)
        }
        return sqrt(sumSquares / samples.size)
    }

    /**
     * Estimate noise floor using statistical method
     * Takes the RMS of the quietest 10% of samples
     */
    private fun estimateNoiseFloor(samples: DoubleArray): Double {
        // Calculate absolute values and sort
        val absSamples = samples.map { kotlin.math.abs(it) }.sorted()

        // Take bottom 10% as noise estimate
        val noiseCount = (absSamples.size * 0.1).toInt().coerceAtLeast(1)
        val noiseSamples = absSamples.take(noiseCount)

        return calculateRMS(noiseSamples.toDoubleArray())
    }

    fun calculateAudioQuality(audioData: ShortArray, frameSizeInSamples: Int): Double {

        val numFrames = audioData.size / frameSizeInSamples

        // --- 2. First Pass: Find Noise Floor ---
        // We calculate the energy of each frame and find the minimum energy.
        // This minimum is assumed to be the "noise floor".
        // Use Double for energy to avoid overflow.
        val frameEnergies = DoubleArray(numFrames)
        var minFrameEnergy = Double.MAX_VALUE

        for (i in 0 until numFrames) {
            var frameEnergy = 0.0
            val start = i * frameSizeInSamples

            for (j in 0 until frameSizeInSamples) {
                val sample = audioData[start + j].toDouble()
                frameEnergy += sample.pow(2)
            }

            frameEnergies[i] = frameEnergy
            if (frameEnergy < minFrameEnergy) {
                minFrameEnergy = frameEnergy
            }
        }

        // --- 3. Second Pass: Calculate Total vs. Noise Energy ---
        // Now, we identify all frames that are "noise" and sum up their energy.

        // Heuristic: A "noise" frame is any frame with energy less than
        // 1.5x the minimum energy. You may need to tune this multiplier!
        val noiseThreshold = minFrameEnergy * 1.5

        var totalEnergy = 0.0          // Your "Total Signal" (energy of all frames)
        var totalNoiseEnergy = 0.0   // Total energy from "noise-only" frames
        var noiseSamplesCount = 0    // Total samples from "noise-only" frames

        for (frameEnergy in frameEnergies) {
            totalEnergy += frameEnergy

            if (frameEnergy < noiseThreshold) {
                totalNoiseEnergy += frameEnergy
                noiseSamplesCount += frameSizeInSamples
            }
        }

        // --- 4. Final Calculation ---
        // Handle edge cases where no noise or total signal is found.
        if (totalEnergy == 0.0) {
            return 1.0 // No signal at all, technically "perfect" (no noise)
        }
        if (noiseSamplesCount == 0) {
            // No silence was found. This means the signal was constant.
            // We can't estimate noise, so assume quality is good.
            return 1.0
        }

        // Calculate the average energy *per sample* for the noise
        val avgNoisePowerPerSample = totalNoiseEnergy / noiseSamplesCount

        // Estimate the total noise energy across the *entire* clip
        val totalEstimatedNoise = avgNoisePowerPerSample * audioData.size

        // Apply your formula: (Total Signal - Noise) / Total Signal
        val quality = (totalEnergy - totalEstimatedNoise) / totalEnergy

        // Clamp the value between 0.0 and 1.0
        // (Noise estimation can sometimes be imperfect)
        return quality.coerceIn(0.0, 1.0)
    }

    /**
     * Convert ByteArray to 16-bit PCM samples (short values)
     */
    private fun convertBytesToShortArray(bytes: ShortArray): DoubleArray {
        val shorts = DoubleArray(bytes.size / 2)
        for (i in shorts.indices) {
            val index = i * 2
            if (index + 1 < bytes.size) {
                // Convert two bytes to short (little-endian)
                val short = ((bytes[index + 1].toInt() shl 8) or
                        (bytes[index].toInt() and 0xFF)).toShort()
                shorts[i] = short.toDouble()
            }
        }
        return shorts
    }

    /**
     * Calculate multiple audio quality metrics at once
     */
    fun analyzeAudioQuality(
        audioData: ShortArray,
        audioProcessor: AudioProcessor,
    ): AudioQualityMetrics? {
        if (audioData.isEmpty()) return null

        val quality = audioProcessor.analyzeAudio(audioData)
        return quality
    }
}

data class AudioQualityMetrics(
    val stoi: Float,      // Speech Intelligibility (0.0 - 1.0)
    val pesq: Float,      // Perceptual Quality (-0.5 - 4.5)
    val siSDR: Float      // Signal Distortion Ratio (dB)
) {
    fun getQualityScore(): Double {
        // Simple overall score (0-5 stars)
        val stoiScore = (stoi * 5).coerceIn(0f, 5f)
        val pesqScore = ((pesq + 0.5) / 5.0 * 5).coerceIn(0.0, 5.0)
        return (stoiScore + pesqScore) / 2f
    }

    fun getQualityLabel(): String = when {
        getQualityScore() >= 4.0 -> "Excellent"
        getQualityScore() >= 3.0 -> "Good"
        getQualityScore() >= 2.0 -> "Fair"
        else -> "Poor"
    }
}