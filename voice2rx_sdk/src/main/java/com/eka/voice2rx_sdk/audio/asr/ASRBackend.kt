package com.eka.voice2rx_sdk.audio.asr

/**
 * Enum for ASR backend selection
 */
enum class ASRBackend {
    /**
     * Whisper.cpp - slower but more accurate for English
     */
    WHISPER,

    /**
     * Sherpa-ONNX - faster, supports multiple languages including Hindi
     */
    SHERPA,

    /**
     * IndicConformer - Hindi ASR using ONNX Runtime with RNNT decoder
     */
    INDIC_CONFORMER
}
