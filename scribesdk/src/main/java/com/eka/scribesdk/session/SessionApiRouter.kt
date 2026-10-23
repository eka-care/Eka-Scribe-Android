package com.eka.scribesdk.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the backend used to read, edit and convert a session.
 *
 * - Recorded on this device: the flow it was recorded with (stored in the local DB), so a legacy
 *   session that is still uploading keeps its legacy calls.
 * - Not on this device (web, extension, desktop, another phone): the Sessions API when it is
 *   enabled, as the web does for every session. If the Sessions API does not know the session
 *   (an old pre-migration one), it falls back to legacy for good via [fallBackToLegacy].
 */
internal class SessionApiRouter(
    private val sessionsApiEnabled: Boolean,
    /** true/false for a session in the local DB (whether it was recorded on the Sessions API); null when it is not. */
    private val recordedOnSessionsApi: suspend (sessionId: String) -> Boolean?
) {
    private val legacyRemoteSessions = ConcurrentHashMap.newKeySet<String>()

    suspend fun usesSessionsApi(sessionId: String): Boolean =
        recordedOnSessionsApi(sessionId)
            ?: (sessionsApiEnabled && sessionId !in legacyRemoteSessions)

    /**
     * Called when the Sessions API answered "not found". Returns true when the caller should retry
     * on the legacy APIs: only for sessions that are not on this device.
     */
    suspend fun fallBackToLegacy(sessionId: String): Boolean {
        if (recordedOnSessionsApi(sessionId) != null) return false
        legacyRemoteSessions += sessionId
        return true
    }
}
