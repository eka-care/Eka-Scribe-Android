package com.eka.scribesdk.analyser

/**
 * Represents the current state of the SQUIM audio quality analyser.
 * Exposed to clients via [com.eka.scribesdk.api.EkaScribe.analyserStateFlow].
 */
sealed class AnalyserState {
    /** Analyser is disabled via config. */
    object Disabled : AnalyserState()

    /** Waiting to start download. */
    object Idle : AnalyserState()

    /** Model is being downloaded from CDN. */
    data class Downloading(val progressPercent: Int = -1) : AnalyserState()

    /** Model is downloaded and analyser is ready for inference. */
    data class Ready(val modelPath: String) : AnalyserState()

    /** Download or model loading failed. */
    data class Failed(val error: String) : AnalyserState()
}
