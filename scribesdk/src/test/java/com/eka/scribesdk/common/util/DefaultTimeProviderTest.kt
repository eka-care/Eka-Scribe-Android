package com.eka.scribesdk.common.util

import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTimeProviderTest {

    @Test
    fun `nowMillis returns current time`() {
        val provider = DefaultTimeProvider()
        val before = System.currentTimeMillis()
        val result = provider.nowMillis()
        val after = System.currentTimeMillis()

        assertTrue("Time should be >= before", result >= before)
        assertTrue("Time should be <= after", result <= after)
    }

    @Test
    fun `nowMillis returns increasing values`() {
        val provider = DefaultTimeProvider()
        val first = provider.nowMillis()
        val second = provider.nowMillis()

        assertTrue("Second call should return >= first", second >= first)
    }
}
