package com.eka.scribesdk.chunker

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkConfigTest {

    @Test
    fun `default values are correct`() {
        val config = ChunkConfig()
        assertEquals(10, config.preferredDurationSec)
        assertEquals(20, config.desperationDurationSec)
        assertEquals(25, config.maxDurationSec)
        assertEquals(0.5, config.longSilenceSec, 0.001)
        assertEquals(0.1, config.shortSilenceSec, 0.001)
        assertEquals(0.5, config.overlapDurationSec, 0.001)
    }

    @Test
    fun `custom values override defaults`() {
        val config = ChunkConfig(
            preferredDurationSec = 15,
            desperationDurationSec = 25,
            maxDurationSec = 30,
            longSilenceSec = 1.0,
            shortSilenceSec = 0.2,
            overlapDurationSec = 1.0
        )
        assertEquals(15, config.preferredDurationSec)
        assertEquals(25, config.desperationDurationSec)
        assertEquals(30, config.maxDurationSec)
        assertEquals(1.0, config.longSilenceSec, 0.001)
        assertEquals(0.2, config.shortSilenceSec, 0.001)
        assertEquals(1.0, config.overlapDurationSec, 0.001)
    }

    @Test
    fun `data class equality`() {
        val a = ChunkConfig()
        val b = ChunkConfig()
        assertEquals(a, b)
    }

    @Test
    fun `copy works correctly`() {
        val original = ChunkConfig()
        val copy = original.copy(preferredDurationSec = 20)
        assertEquals(20, copy.preferredDurationSec)
        assertEquals(original.desperationDurationSec, copy.desperationDurationSec)
    }

    @Test
    fun `zero overlap config`() {
        val config = ChunkConfig(overlapDurationSec = 0.0)
        assertEquals(0.0, config.overlapDurationSec, 0.001)
    }
}
