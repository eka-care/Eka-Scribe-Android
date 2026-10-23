package com.eka.scribesdk.session

import com.eka.scribesdk.common.logging.NoOpLogger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TemplateNameResolverTest {

    private var catalogCalls = 0

    private fun resolver(
        saved: Map<String, String> = emptyMap(),
        catalog: () -> Map<String, String>? = { mapOf("eka_emr_template" to "Eka EMR", "t-1" to "SOAP Note") }
    ) = TemplateNameResolver(
        savedNames = { saved },
        fetchCatalog = { catalogCalls++; catalog() },
        logger = NoOpLogger()
    )

    @Test
    fun `saved names skip the catalog call`() = runTest {
        val names = resolver(saved = mapOf("t-1" to "My SOAP")).namesFor("s", listOf("t-1"))

        assertEquals(mapOf("t-1" to "My SOAP"), names)
        assertEquals(0, catalogCalls)
    }

    @Test
    fun `unnamed templates come from the catalog, saved names win`() = runTest {
        val names = resolver(saved = mapOf("t-1" to "My SOAP"))
            .namesFor("s", listOf("t-1", "eka_emr_template"))

        assertEquals("My SOAP", names["t-1"])
        assertEquals("Eka EMR", names["eka_emr_template"])
    }

    @Test
    fun `catalog is cached and refetched only for a template it lacks`() = runTest {
        var catalog = mapOf("t-1" to "SOAP Note")
        val resolver = resolver(catalog = { catalog })

        resolver.namesFor("s", listOf("t-1"))
        resolver.namesFor("s", listOf("t-1"))
        assertEquals(1, catalogCalls)

        catalog = mapOf("t-1" to "SOAP Note", "t-new" to "New Template")
        assertEquals("New Template", resolver.namesFor("s", listOf("t-new"))["t-new"])
        assertEquals(2, catalogCalls)
    }

    @Test
    fun `catalog failure leaves names empty so the id is shown`() = runTest {
        val names = resolver(catalog = { null }).namesFor("s", listOf("t-1"))

        assertEquals(emptyMap<String, String>(), names)
    }

    @Test
    fun `blank saved names fall back to the catalog`() = runTest {
        val names = resolver(saved = mapOf("t-1" to " ")).namesFor("s", listOf("t-1"))

        assertEquals("SOAP Note", names["t-1"])
    }
}
