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
            AudioQuality(stoi = 0.85f, pesq = 3.5f, siSDR = 15.0f, overallScore = 0.75f)
        assertEquals(0.85f, quality.stoi, 0.001f)
        assertEquals(3.5f, quality.pesq, 0.001f)
        assertEquals(15.0f, quality.siSDR, 0.001f)
        assertEquals(0.75f, quality.overallScore, 0.001f)
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }
}
