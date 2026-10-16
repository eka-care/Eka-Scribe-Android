package com.eka.scribesdk.session

import com.eka.scribesdk.api.EkaScribeConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionDurationLimiterTest {

    @Test
    fun `fires once recorded time reaches the limit`() = runTest {
        var fired = 0
        SessionDurationLimiter(limitMs = 5_000, isRecording = { true }, isActive = { true }) { fired++ }.run()

        assertEquals(1, fired)
        assertEquals(5_000L, testScheduler.currentTime)
    }

    @Test
    fun `paused time does not count`() = runTest {
        var fired = 0
        // Paused for the first 3 seconds, then recording
        SessionDurationLimiter(
            limitMs = 5_000,
            isRecording = { testScheduler.currentTime > 3_000 },
            isActive = { true }
        ) { fired++ }.run()

        assertEquals(1, fired)
        assertEquals(8_000L, testScheduler.currentTime)
    }

    @Test
    fun `stops quietly when the session ends first`() = runTest {
        var fired = 0
        SessionDurationLimiter(
            limitMs = 60_000,
            isRecording = { true },
            isActive = { testScheduler.currentTime < 10_000 }
        ) { fired++ }.run()

        assertEquals(0, fired)
    }

    @Test
    fun `limits follow the model`() {
        assertEquals(600, EkaScribeConfig.maxSessionSeconds("lite"))
        assertEquals(600, EkaScribeConfig.maxSessionSeconds("LITE"))
        assertEquals(3600, EkaScribeConfig.maxSessionSeconds("pro"))
        assertEquals(3600, EkaScribeConfig.maxSessionSeconds(null))
    }
}
