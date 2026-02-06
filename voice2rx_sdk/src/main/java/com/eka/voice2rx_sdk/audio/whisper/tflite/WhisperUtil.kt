package com.eka.voice2rx_sdk.audio.whisper.tflite

import android.util.Log
import java.io.FileInputStream
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin

/**
 * Utility class for Whisper TFLite inference.
 * Handles vocabulary loading, mel spectrogram computation, and token-to-word conversion.
 */
class WhisperUtil {

    companion object {
        private const val TAG = "WhisperUtil"

        const val WHISPER_SAMPLE_RATE = 16000
        const val WHISPER_N_FFT = 400
        const val WHISPER_N_MEL = 80
        const val WHISPER_HOP_LENGTH = 160
        const val WHISPER_CHUNK_SIZE = 30
        const val WHISPER_MEL_LEN = 3000
    }

    private val vocab = WhisperVocab()
    private val filters = WhisperFilter()
    private val mel = WhisperMel()

    // Token accessors
    val tokenTranslate: Int get() = vocab.tokenTRANSLATE
    val tokenTranscribe: Int get() = vocab.tokenTRANSCRIBE
    val tokenEOT: Int get() = vocab.tokenEOT
    val tokenSOT: Int get() = vocab.tokenSOT
    val tokenPREV: Int get() = vocab.tokenPREV
    val tokenSOLM: Int get() = vocab.tokenSOLM
    val tokenNOT: Int get() = vocab.tokenNOT
    val tokenBEG: Int get() = vocab.tokenBEG

    /**
     * Get word for a token ID.
     */
    fun getWordFromToken(token: Int): String? = vocab.tokenToWord[token]

    /**
     * Load filters and vocabulary from the pre-generated .bin file.
     *
     * @param multilingual Whether to use multilingual vocabulary
     * @param vocabPath Path to the vocabulary .bin file
     * @return true if loaded successfully
     */
    fun loadFiltersAndVocab(multilingual: Boolean, vocabPath: String): Boolean {
        return try {
            val fileInputStream = FileInputStream(vocabPath)
            val fileChannel = fileInputStream.channel
            val vocabBuf = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size())
            vocabBuf.order(ByteOrder.nativeOrder())

            Log.d(TAG, "Vocab file size: ${vocabBuf.limit()}")

            // Check magic number: USEN
            val magic = vocabBuf.int
            if (magic != 0x5553454e) {
                Log.e(TAG, "Invalid vocab file (bad magic: $magic), $vocabPath")
                fileInputStream.close()
                return false
            }
            Log.d(TAG, "Magic number: $magic")

            // Load mel filters
            filters.nMel = vocabBuf.int
            filters.nFft = vocabBuf.int
            Log.d(TAG, "n_mel:${filters.nMel}, n_fft:${filters.nFft}")

            filters.data = FloatArray(filters.nMel * filters.nFft)
            for (i in filters.data.indices) {
                filters.data[i] = vocabBuf.float
            }

            // Load vocabulary
            val nVocab = vocabBuf.int
            Log.d(TAG, "nVocab: $nVocab")

            for (i in 0 until nVocab) {
                val len = vocabBuf.int
                val wordBytes = ByteArray(len)
                vocabBuf.get(wordBytes)
                val word = String(wordBytes)
                vocab.tokenToWord[i] = word
            }

            // Add additional vocab IDs
            val nVocabAdditional = if (!multilingual) {
                vocab.nVocabEnglish
            } else {
                vocab.tokenEOT++
                vocab.tokenSOT++
                vocab.tokenPREV++
                vocab.tokenSOLM++
                vocab.tokenNOT++
                vocab.tokenBEG++
                vocab.nVocabMultilingual
            }

            for (i in nVocab until nVocabAdditional) {
                val word = when {
                    i > vocab.tokenBEG -> "[_TT_${i - vocab.tokenBEG}]"
                    i == vocab.tokenEOT -> "[_EOT_]"
                    i == vocab.tokenSOT -> "[_SOT_]"
                    i == vocab.tokenPREV -> "[_PREV_]"
                    i == vocab.tokenNOT -> "[_NOT_]"
                    i == vocab.tokenBEG -> "[_BEG_]"
                    else -> "[_extra_token_$i]"
                }
                vocab.tokenToWord[i] = word
            }

            fileInputStream.close()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading filters and vocab", e)
            false
        }
    }

    /**
     * Compute mel spectrogram from audio samples.
     *
     * @param samples Audio samples (WHISPER_SAMPLE_RATE * WHISPER_CHUNK_SIZE = 480000)
     * @param nSamples Number of samples
     * @param nThreads Number of threads to use
     * @return Mel spectrogram data
     */
    fun getMelSpectrogram(samples: FloatArray, nSamples: Int, nThreads: Int): FloatArray {
        val fftSize = WHISPER_N_FFT
        val fftStep = WHISPER_HOP_LENGTH

        mel.nMel = WHISPER_N_MEL
        mel.nLen = nSamples / fftStep
        mel.data = FloatArray(mel.nMel * mel.nLen)

        // Hanning window
        val hann = FloatArray(fftSize) { i ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * i / fftSize))).toFloat()
        }

        val nFft = 1 + fftSize / 2

        // Multi-threaded mel calculation
        val workers = mutableListOf<Thread>()
        for (iw in 0 until nThreads) {
            val ith = iw
            val thread = Thread {
                val fftIn = FloatArray(fftSize)
                val fftOut = FloatArray(fftSize * 2)

                var i = ith
                while (i < mel.nLen) {
                    fftIn.fill(0f)
                    val offset = i * fftStep

                    // Apply Hanning window
                    for (j in 0 until fftSize) {
                        if (offset + j < nSamples) {
                            fftIn[j] = hann[j] * samples[offset + j]
                        }
                    }

                    // FFT -> mag^2
                    fft(fftIn, fftOut)
                    for (j in 0 until fftSize) {
                        fftOut[j] =
                            fftOut[2 * j] * fftOut[2 * j] + fftOut[2 * j + 1] * fftOut[2 * j + 1]
                    }

                    for (j in 1 until fftSize / 2) {
                        fftOut[j] += fftOut[fftSize - j]
                    }

                    // Mel spectrogram
                    for (j in 0 until mel.nMel) {
                        var sum = 0.0
                        for (k in 0 until nFft) {
                            sum += fftOut[k] * filters.data[j * nFft + k]
                        }
                        if (sum < 1e-10) sum = 1e-10
                        sum = log10(sum)
                        mel.data[j * mel.nLen + i] = sum.toFloat()
                    }

                    i += nThreads
                }
            }
            workers.add(thread)
            thread.start()
        }

        // Wait for all threads
        workers.forEach { it.join() }

        // Clamping and normalization
        var mmax = -1e20
        for (i in 0 until mel.nMel * mel.nLen) {
            if (mel.data[i] > mmax) mmax = mel.data[i].toDouble()
        }

        mmax -= 8.0
        for (i in 0 until mel.nMel * mel.nLen) {
            if (mel.data[i] < mmax) mel.data[i] = mmax.toFloat()
            mel.data[i] = ((mel.data[i] + 4.0) / 4.0).toFloat()
        }

        return mel.data
    }

    private fun dft(input: FloatArray, output: FloatArray) {
        val inSize = input.size
        for (k in 0 until inSize) {
            var re = 0f
            var im = 0f
            for (n in 0 until inSize) {
                val angle = (2 * Math.PI * k * n / inSize).toFloat()
                re += input[n] * cos(angle.toDouble()).toFloat()
                im -= input[n] * sin(angle.toDouble()).toFloat()
            }
            output[k * 2] = re
            output[k * 2 + 1] = im
        }
    }

    private fun fft(input: FloatArray, output: FloatArray) {
        val inSize = input.size
        if (inSize == 1) {
            output[0] = input[0]
            output[1] = 0f
            return
        }

        if (inSize % 2 == 1) {
            dft(input, output)
            return
        }

        val even = FloatArray(inSize / 2)
        val odd = FloatArray(inSize / 2)

        for (i in 0 until inSize) {
            if (i % 2 == 0) even[i / 2] = input[i]
            else odd[i / 2] = input[i]
        }

        val evenFft = FloatArray(inSize)
        val oddFft = FloatArray(inSize)

        fft(even, evenFft)
        fft(odd, oddFft)

        for (k in 0 until inSize / 2) {
            val theta = (2 * Math.PI * k / inSize).toFloat()
            val re = cos(theta.toDouble()).toFloat()
            val im = -sin(theta.toDouble()).toFloat()
            val reOdd = oddFft[2 * k]
            val imOdd = oddFft[2 * k + 1]

            output[2 * k] = evenFft[2 * k] + re * reOdd - im * imOdd
            output[2 * k + 1] = evenFft[2 * k + 1] + re * imOdd + im * reOdd
            output[2 * (k + inSize / 2)] = evenFft[2 * k] - re * reOdd + im * imOdd
            output[2 * (k + inSize / 2) + 1] = evenFft[2 * k + 1] - re * imOdd - im * reOdd
        }
    }

    // Inner classes
    private class WhisperVocab {
        var tokenEOT = 50256
        var tokenSOT = 50257
        var tokenPREV = 50360
        var tokenSOLM = 50361
        var tokenNOT = 50362
        var tokenBEG = 50363

        val tokenTRANSLATE = 50358
        val tokenTRANSCRIBE = 50359

        val nVocabEnglish = 51864
        val nVocabMultilingual = 51865

        val tokenToWord = mutableMapOf<Int, String>()
    }

    private class WhisperFilter {
        var nMel = 0
        var nFft = 0
        lateinit var data: FloatArray
    }

    private class WhisperMel {
        var nLen = 0
        var nMel = 0
        lateinit var data: FloatArray
    }
}
