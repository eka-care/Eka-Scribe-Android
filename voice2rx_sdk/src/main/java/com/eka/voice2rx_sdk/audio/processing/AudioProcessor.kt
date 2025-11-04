package com.eka.voice2rx_sdk.audio.processing

import android.content.Context
import com.eka.voice2rx_sdk.common.AudioQualityMetrics

class AudioProcessor {
    private var squimAnalyzer: SquimAnalyzer? = null

    constructor(context: Context, isAudioQualityAnalysisEnabled: Boolean) {
        if (isAudioQualityAnalysisEnabled) {
            squimAnalyzer = SquimAnalyzer(context)
        }
    }

    fun analyzeAudio(audioData: ShortArray): AudioQualityMetrics? {
        return squimAnalyzer?.analyze(audioData)
    }

    fun release() {
        squimAnalyzer?.release()
    }
}