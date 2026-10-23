package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * templateId -> display name for Sessions API results, which carry no template names.
 *
 * Names the host app passed at session start ([OutputTemplate.templateName]) win. Any template
 * still unnamed falls back to the template catalog (`GET voice/api/v1/template`), which is
 * fetched once and cached; it is refetched only when a result has a template the cache lacks
 * (e.g. one created after the first fetch). With nothing found the mapper shows the raw id.
 */
internal class TemplateNameResolver(
    private val savedNames: suspend (sessionId: String) -> Map<String, String>,
    /** templateId -> title, or null when the catalog could not be fetched. */
    private val fetchCatalog: suspend () -> Map<String, String>?,
    private val logger: Logger
) {
    companion object {
        private const val TAG = "TemplateNameResolver"
    }

    private val mutex = Mutex()
    private var catalog: Map<String, String> = emptyMap()

    suspend fun namesFor(sessionId: String, templateIds: Collection<String>): Map<String, String> {
        val saved = savedNames(sessionId).filterValues { it.isNotBlank() }
        val missing = templateIds.filter { it !in saved }
        if (missing.isEmpty()) return saved
        return catalogFor(missing) + saved
    }

    private suspend fun catalogFor(templateIds: List<String>): Map<String, String> = mutex.withLock {
        if (templateIds.any { it !in catalog }) {
            val fetched = try {
                fetchCatalog()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (fetched != null) {
                catalog = fetched.filterValues { it.isNotBlank() }
            } else {
                logger.warn(TAG, "Template catalog unavailable; unnamed templates will show their id")
            }
        }
        catalog
    }
}
