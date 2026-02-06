package com.eka.voice2rx_sdk.audio.whisper.tflite

import java.io.IOException

/**
 * Interface for Whisper TFLite engine implementations.
 */
interface WhisperEngine {

    /**
     * Check if the engine is initialized and ready for inference.
     */
    fun isInitialized(): Boolean

    /**
     * Initialize the engine with model and vocabulary paths.
     *
     * @param modelPath Path to the .tflite model file
     * @param vocabPath Path to the vocabulary .bin file
     * @param multilingual Whether to use multilingual vocabulary
     * @throws IOException if model or vocab file cannot be loaded
     */
    @Throws(IOException::class)
    fun initialize(modelPath: String, vocabPath: String, multilingual: Boolean): Boolean

    /**
     * Release resources and deinitialize the engine.
     */
    fun deinitialize()

    /**
     * Transcribe audio from a WAV file.
     *
     * @param wavePath Path to the WAV file (16kHz, mono, 16-bit)
     * @return Transcribed text
     */
    fun transcribeFile(wavePath: String): String

    /**
     * Transcribe audio samples directly.
     *
     * @param samples Audio samples as float array (normalized to [-1, 1])
     * @return Transcribed text
     */
    fun transcribeBuffer(samples: FloatArray): String
}
