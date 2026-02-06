package com.eka.voice2rx_sdk.audio.asr.indicconformer

import java.lang.ref.SoftReference
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Mel Spectrogram processor for IndicConformer ASR.
 * Converts raw audio to log mel spectrogram features matching NeMo's AudioToMelSpectrogramPreprocessor.
 * Uses buffer pooling to minimize memory allocations.
 */
class MelSpectrogramProcessor(
    private val sampleRate: Int = 16000,
    private val nFft: Int = 512,
    private val hopLength: Int = 160,
    private val winLength: Int = 400,
    private val nMels: Int = 80,
    private val preemph: Float = 0.97f,
    private val dither: Float = 1e-5f
) {
    // Precompute Hann window
    private val hannWindow = FloatArray(winLength) { i ->
        (0.5 * (1 - cos(2.0 * PI * i / (winLength - 1)))).toFloat()
    }

    // Precompute mel filterbank matrix (nMels x (nFft/2 + 1))
    private val melFilterbank: Array<FloatArray> = createMelFilterbank()

    // Random number generator for dithering
    private val random = Random()

    // Reusable buffers (using SoftReference so GC can reclaim if under pressure)
    private var preemphasizedBuffer: SoftReference<FloatArray>? = null
    private var fftRealBuffer: SoftReference<FloatArray>? = null
    private var fftImagBuffer: SoftReference<FloatArray>? = null

    /**
     * Compute mel spectrogram from raw audio samples.
     *
     * @param audio Raw audio samples normalized to [-1, 1]
     * @return Mel spectrogram of shape (80, numFrames)
     */
    fun compute(audio: FloatArray): Array<FloatArray> {
        val numFrames = maxOf(1, (audio.size - winLength) / hopLength + 1)

        // Get or create preemphasis buffer
        var preemphasized = preemphasizedBuffer?.get()
        if (preemphasized == null || preemphasized.size < audio.size) {
            preemphasized = FloatArray(audio.size)
            preemphasizedBuffer = SoftReference(preemphasized)
        }

        // 1. Dither + Pre-emphasis in single pass
        preemphasized[0] = audio[0] + (dither * random.nextGaussian().toFloat())
        for (i in 1 until audio.size) {
            val dithered = audio[i] + (dither * random.nextGaussian().toFloat())
            val prevDithered = audio[i - 1] + (dither * random.nextGaussian().toFloat())
            preemphasized[i] = dithered - preemph * prevDithered
        }

        // Allocate output (this needs to be fresh each time as it's returned)
        val melSpec = Array(nMels) { FloatArray(numFrames) }

        // Temporary buffer for power spectrum per frame
        val powerSpec = FloatArray(nFft / 2 + 1)
        val windowed = FloatArray(nFft)

        // 2. STFT: frame, window, FFT
        for (frame in 0 until numFrames) {
            val start = frame * hopLength

            // Clear and fill windowed buffer
            windowed.fill(0f)
            for (i in 0 until minOf(winLength, audio.size - start)) {
                windowed[i] = preemphasized[start + i] * hannWindow[i]
            }

            // FFT and compute power spectrum into reused buffer
            performFFTInPlace(windowed, powerSpec)

            // 3. Apply mel filterbank directly
            for (mel in 0 until nMels) {
                var sum = 0f
                for (k in 0..nFft / 2) {
                    sum += melFilterbank[mel][k] * powerSpec[k]
                }
                // 4. Log transform
                melSpec[mel][frame] = ln(sum + 1e-20f)
            }
        }

        // 5. Per-feature normalization
        for (mel in 0 until nMels) {
            var sum = 0f
            for (frame in 0 until numFrames) {
                sum += melSpec[mel][frame]
            }
            val mean = sum / numFrames

            var varSum = 0f
            for (frame in 0 until numFrames) {
                val diff = melSpec[mel][frame] - mean
                varSum += diff * diff
            }
            val std = sqrt(varSum / numFrames) + 1e-8f

            for (frame in 0 until numFrames) {
                melSpec[mel][frame] = (melSpec[mel][frame] - mean) / std
            }
        }

        return melSpec
    }

    /**
     * Flatten mel spectrogram to 1D array for ONNX input.
     * Output shape: (1, 80, T) in row-major order
     */
    fun flattenForOnnx(melSpec: Array<FloatArray>): FloatArray {
        val numFrames = melSpec[0].size
        val flat = FloatArray(nMels * numFrames)
        for (mel in 0 until nMels) {
            for (t in 0 until numFrames) {
                flat[mel * numFrames + t] = melSpec[mel][t]
            }
        }
        return flat
    }

    /**
     * Create mel filterbank matrix.
     * 80 triangular filters spanning 0 Hz to Nyquist (8000 Hz for 16kHz sample rate)
     */
    private fun createMelFilterbank(): Array<FloatArray> {
        val fMin = 0f
        val fMax = sampleRate / 2f // Nyquist frequency
        val numFreqs = nFft / 2 + 1

        // Convert Hz to Mel scale
        fun hzToMel(hz: Float): Float = 2595f * log10(1f + hz / 700f)
        fun melToHz(mel: Float): Float = 700f * (10f.pow(mel / 2595f) - 1f)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)

        // Create nMels + 2 mel points (including edges)
        val melPoints = FloatArray(nMels + 2) { i ->
            melMin + i * (melMax - melMin) / (nMels + 1)
        }

        // Convert mel points to Hz
        val hzPoints = melPoints.map { melToHz(it) }

        // Convert Hz to FFT bin indices
        val binIndices = hzPoints.map { hz ->
            ((nFft + 1) * hz / sampleRate).toInt()
        }

        // Create filterbank
        val filterbank = Array(nMels) { FloatArray(numFreqs) }

        for (m in 0 until nMels) {
            val fLeft = binIndices[m]
            val fCenter = binIndices[m + 1]
            val fRight = binIndices[m + 2]

            // Rising edge
            for (k in fLeft until fCenter) {
                if (k < numFreqs && fCenter > fLeft) {
                    filterbank[m][k] = (k - fLeft).toFloat() / (fCenter - fLeft)
                }
            }

            // Falling edge
            for (k in fCenter until fRight) {
                if (k < numFreqs && fRight > fCenter) {
                    filterbank[m][k] = (fRight - k).toFloat() / (fRight - fCenter)
                }
            }
        }

        return filterbank
    }

    // Reusable FFT buffers (class-level to avoid reallocation)
    private val fftPaddedSize = nextPowerOf2(nFft)
    private val fftReal = FloatArray(fftPaddedSize)
    private val fftImag = FloatArray(fftPaddedSize)

    /**
     * In-place FFT that writes power spectrum directly to output.
     * Reuses internal buffers to minimize allocations.
     */
    private fun performFFTInPlace(input: FloatArray, powerSpecOut: FloatArray) {
        val n = input.size
        val paddedSize = fftPaddedSize

        // Copy input to real buffer, clear imag
        fftReal.fill(0f)
        fftImag.fill(0f)
        input.copyInto(fftReal, endIndex = minOf(n, paddedSize))

        // Bit reversal permutation
        var j = 0
        for (i in 0 until paddedSize - 1) {
            if (i < j) {
                val tempReal = fftReal[i]
                val tempImag = fftImag[i]
                fftReal[i] = fftReal[j]
                fftImag[i] = fftImag[j]
                fftReal[j] = tempReal
                fftImag[j] = tempImag
            }
            var k = paddedSize / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }

        // Cooley-Tukey FFT
        var mmax = 1
        while (paddedSize > mmax) {
            val istep = mmax * 2
            val theta = -PI / mmax
            val wpr = cos(theta).toFloat()
            val wpi = kotlin.math.sin(theta).toFloat()
            var wr = 1.0f
            var wi = 0.0f

            for (m in 0 until mmax) {
                for (i in m until paddedSize step istep) {
                    val j2 = i + mmax
                    val tempReal = wr * fftReal[j2] - wi * fftImag[j2]
                    val tempImag = wr * fftImag[j2] + wi * fftReal[j2]
                    fftReal[j2] = fftReal[i] - tempReal
                    fftImag[j2] = fftImag[i] - tempImag
                    fftReal[i] += tempReal
                    fftImag[i] += tempImag
                }
                val tempWr = wr
                wr = wr * wpr - wi * wpi
                wi = wi * wpr + tempWr * wpi
            }
            mmax = istep
        }

        // Write power spectrum directly to output
        for (i in 0..n / 2) {
            powerSpecOut[i] = fftReal[i] * fftReal[i] + fftImag[i] * fftImag[i]
        }
    }

    private fun nextPowerOf2(n: Int): Int {
        var power = 1
        while (power < n) power *= 2
        return power
    }

    /**
     * Clear references to allow GC
     */
    fun clearBuffers() {
        preemphasizedBuffer?.clear()
        preemphasizedBuffer = null
    }
}
