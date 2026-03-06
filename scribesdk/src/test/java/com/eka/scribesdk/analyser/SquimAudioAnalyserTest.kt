package com.eka.scribesdk.analyser

import com.eka.scribesdk.common.logging.Logger
import com.eka.scribesdk.recorder.AudioFrame
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SquimAudioAnalyserTest {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
    }

    private val logger = object : Logger {
        override fun debug(tag: String, message: String) {}
        override fun info(tag: String, message: String) {}
        override fun warn(tag: String, message: String, throwable: Throwable?) {}
        override fun error(tag: String, message: String, throwable: Throwable?) {}
    }

    private fun makeFrame(index: Long = 0, pcmSize: Int = FRAME_SIZE): AudioFrame {
        return AudioFrame(
            pcm = ShortArray(pcmSize) { 100 },
            timestampMs = index * 32,
            sampleRate = SAMPLE_RATE,
            frameIndex = index
        )
    }

    private val fakeQuality = AudioQuality(
        stoi = 0.9f,
        pesq = 4.0f,
        siSDR = 20.0f,
        overallScore = 0.85f
    )

    /**
     * Creates a mock SquimModelProvider that loads instantly and returns [fakeQuality].
     */
    private fun createMockProvider(
        shouldFailLoad: Boolean = false,
        loadDelayMs: Long = 0L
    ): SquimModelProvider {
        val provider = mockk<SquimModelProvider>(relaxed = true)
        every { provider.load() } answers {
            if (loadDelayMs > 0) Thread.sleep(loadDelayMs)
            if (shouldFailLoad) throw RuntimeException("Simulated model load failure")
        }
        every { provider.isLoaded() } returns !shouldFailLoad
        every { provider.analyse(any()) } returns fakeQuality
        return provider
    }

    // =====================================================================
    // LAZY LOADING TESTS
    // =====================================================================

    @Test
    fun `model loads in background without blocking constructor`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = createMockProvider(loadDelayMs = 100)

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            logger = logger
        )

        // Deterministically wait for background load — polls until verified or timeout
        verify(timeout = 5000) { provider.load() }

        analyser.release()
    }

    @Test
    fun `frames are dropped before model is loaded`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // Use a provider with a long load delay so model isn't ready during test
        val provider = createMockProvider(loadDelayMs = 2000)

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            analysisDurationMs = 0,
            logger = logger
        )

        // Submit frames immediately — model is still loading
        repeat(100) { i ->
            analyser.submitFrame(makeFrame(i.toLong()))
        }

        // No analysis should have been triggered (frames were dropped)
        verify(exactly = 0) { provider.analyse(any()) }

        analyser.release()
    }

    @Test
    fun `frames are processed after model is loaded`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = createMockProvider() // Instant load

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            analysisDurationMs = 0,
            logger = logger
        )

        // Wait for model to load in background
        delay(200)

        // Submit frames — they should be processed
        repeat(50) { i ->
            analyser.submitFrame(makeFrame(i.toLong()))
        }

        // Give the inference coroutine time to execute
        delay(300)

        verify(atLeast = 1) { provider.analyse(any()) }

        analyser.release()
    }

    @Test
    fun `qualityFlow emits after model loads and frames are submitted`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = createMockProvider()

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            analysisDurationMs = 0,
            logger = logger
        )

        // Wait for model to load (real time, not virtual)
        delay(300)

        // Submit enough frames to trigger analysis
        repeat(50) { i ->
            analyser.submitFrame(makeFrame(i.toLong()))
        }

        // qualityFlow should emit at least one value (real-time timeout)
        val quality = withTimeout(3000) {
            analyser.qualityFlow.first()
        }

        assertEquals(0.9f, quality.stoi, 0.001f)
        assertEquals(4.0f, quality.pesq, 0.001f)
        assertEquals(20.0f, quality.siSDR, 0.001f)
        assertEquals(0.85f, quality.overallScore, 0.001f)

        analyser.release()
    }

    @Test
    fun `analyser gracefully handles model load failure`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = createMockProvider(shouldFailLoad = true)

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            logger = logger
        )

        // Wait for background load attempt to fail
        delay(200)

        // Submitting frames should not crash — silently dropped
        repeat(10) { i ->
            analyser.submitFrame(makeFrame(i.toLong()))
        }

        // No analyse calls should happen when model load failed
        verify(exactly = 0) { provider.analyse(any()) }

        analyser.release()
    }

    @Test
    fun `release cleans up resources`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = createMockProvider()

        val analyser = SquimAudioAnalyser(
            modelProvider = provider,
            scope = scope,
            logger = logger
        )

        delay(200)
        analyser.release()

        verify { provider.unload() }
    }

    private fun assertEquals(expected: Float, actual: Float, delta: Float) {
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), delta.toDouble())
    }
}
