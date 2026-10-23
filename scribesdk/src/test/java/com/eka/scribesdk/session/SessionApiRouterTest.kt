package com.eka.scribesdk.session

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class SessionApiRouterTest {

    private fun router(enabled: Boolean, local: Map<String, Boolean> = emptyMap()) =
        SessionApiRouter(sessionsApiEnabled = enabled, recordedOnSessionsApi = { local[it] })

    @Test
    fun `sessions recorded on this device keep the flow they were recorded with`() = runTest {
        val local = mapOf("new-here" to true, "old-here" to false)

        assertTrue(router(enabled = false, local = local).usesSessionsApi("new-here"))
        assertFalse(router(enabled = true, local = local).usesSessionsApi("old-here"))
    }

    @Test
    fun `sessions from another client follow the Sessions API flag`() = runTest {
        assertTrue(router(enabled = true).usesSessionsApi("sc-web"))
        assertFalse(router(enabled = false).usesSessionsApi("sc-web"))
    }

    @Test
    fun `a remote session the Sessions API does not know stays on legacy`() = runTest {
        val router = router(enabled = true)

        assertTrue(router.fallBackToLegacy("old-remote"))
        assertFalse(router.usesSessionsApi("old-remote"))
        assertTrue(router.usesSessionsApi("other-remote"))
    }

    @Test
    fun `a session recorded on this device never falls back`() = runTest {
        val router = router(enabled = true, local = mapOf("new-here" to true))

        assertFalse(router.fallBackToLegacy("new-here"))
        assertTrue(router.usesSessionsApi("new-here"))
    }
}
