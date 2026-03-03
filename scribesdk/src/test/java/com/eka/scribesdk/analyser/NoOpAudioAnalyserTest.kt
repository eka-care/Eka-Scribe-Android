package com.eka.scribesdk.analyser

import com.eka.scribesdk.recorder.AudioFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class NoOpAudioAnalyserTest {

    private fun makeFrame() = AudioFrame(
        pcm = ShortArray(512),
        timestampMs = 0,
        sampleRate = 16000,
        frameIndex = 0
    )

    @Test
    fun `submitFrame does not crash`() {
        val analyser = NoOpAudioAnalyser()
        // Should not throw
        analyser.submitFrame(makeFrame())
        analyser.submitFrame(makeFrame())
    }

    @Test
    fun `qualityFlow emits nothing`() = runTest {
        val analyser = NoOpAudioAnalyser()
        val emissions = analyser.qualityFlow.toList()
        assertTrue("qualityFlow should emit nothing", emissions.isEmpty())
    }

    @Test
    fun `release does not crash`() {
        val analyser = NoOpAudioAnalyser()
        analyser.release()
        // Double release should also be safe
        analyser.release()
    }

    @Test
    fun `AudioQuality data class holds correct values`() {
        val quality =
            AudioQuality(snr = 25.0f, clipping = 0.1f, loudness = -20.0f, overallScore = 0.85f)
        assertEquals(25.0f, quality.snr, 0.001f)
        assertEquals(0.1f, quality.clipping, 0.001f)
        assertEquals(-20.0f, quality.loudness, 0.001f)
        assertEquals(0.85f, quality.overallScore, 0.001f)
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }
}
