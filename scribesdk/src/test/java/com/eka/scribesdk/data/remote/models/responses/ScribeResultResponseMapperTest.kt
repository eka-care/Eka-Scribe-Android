package com.eka.scribesdk.data.remote.models.responses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class ScribeResultResponseMapperTest {

    @Test
    fun `toSessionResult with null data returns empty result`() {
        val response = ScribeResultResponse(data = null)
        val result = response.toSessionResult("s1")

        assertTrue(result.templates.isEmpty())
        assertNull(result.audioQuality)
    }

    @Test
    fun `toSessionResult with null templateResults returns empty templates`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = ScribeResultResponse.Data.AudioMatrix(quality = 0.95),
                createdAt = null,
                output = null,
                templateResults = null
            )
        )
        val result = response.toSessionResult("s1")

        assertTrue(result.templates.isEmpty())
        assertEquals(0.95, result.audioQuality!!, 0.001)
    }

    @Test
    fun `toSessionResult with null custom and transcript returns empty templates`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = null,
                    integration = null,
                    transcript = null
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters outputs with null value`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = listOf(
                        ScribeResultResponse.Data.Output(
                            errors = null,
                            name = "out",
                            status = ResultStatus.SUCCESS,
                            templateId = "t1",
                            type = OutputType.MARKDOWN,
                            value = null, // null value → filtered
                            warnings = null
                        )
                    ),
                    integration = null,
                    transcript = null
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters outputs with null templateId`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = listOf(
                        ScribeResultResponse.Data.Output(
                            errors = null,
                            name = "out",
                            status = ResultStatus.SUCCESS,
                            templateId = null, // null templateId → filtered
                            type = OutputType.MARKDOWN,
                            value = "base64data",
                            warnings = null
                        )
                    ),
                    integration = null,
                    transcript = null
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters outputs with non-success status`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = listOf(
                        ScribeResultResponse.Data.Output(
                            errors = null,
                            name = "out",
                            status = ResultStatus.IN_PROGRESS,
                            templateId = "t1",
                            type = OutputType.MARKDOWN,
                            value = "data",
                            warnings = null
                        ),
                        ScribeResultResponse.Data.Output(
                            errors = null,
                            name = "fail",
                            status = ResultStatus.FAILURE,
                            templateId = "t2",
                            type = OutputType.TEXT,
                            value = "data",
                            warnings = null
                        )
                    ),
                    integration = null,
                    transcript = null
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters transcript with null value`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = null,
                    integration = null,
                    transcript = listOf(
                        ScribeResultResponse.Data.Transcript(
                            errors = null,
                            lang = "en",
                            status = ResultStatus.SUCCESS,
                            type = OutputType.TEXT,
                            value = null, // null → filtered
                            warnings = null
                        )
                    )
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters transcript with non-success status`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = null,
                    integration = null,
                    transcript = listOf(
                        ScribeResultResponse.Data.Transcript(
                            errors = null,
                            lang = "en",
                            status = ResultStatus.IN_PROGRESS,
                            type = OutputType.TEXT,
                            value = "some data",
                            warnings = null
                        )
                    )
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult filters null items in lists`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = ScribeResultResponse.Data.TemplateResults(
                    custom = listOf(null, null),
                    integration = null,
                    transcript = listOf(null)
                )
            )
        )
        val result = response.toSessionResult("s1")
        assertTrue(result.templates.isEmpty())
    }

    @Test
    fun `toSessionResult maps audioQuality`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = ScribeResultResponse.Data.AudioMatrix(quality = 0.42),
                createdAt = null,
                output = null,
                templateResults = null
            )
        )
        val result = response.toSessionResult("s1")
        assertEquals(0.42, result.audioQuality!!, 0.001)
    }

    @Test
    fun `toSessionResult null audioMatrix gives null quality`() {
        val response = ScribeResultResponse(
            data = ScribeResultResponse.Data(
                audioMatrix = null,
                createdAt = null,
                output = null,
                templateResults = null
            )
        )
        assertNull(response.toSessionResult("s1").audioQuality)
    }
}
