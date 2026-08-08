package io.github.kdroidfilter.storekit.gplay.scrapper.services

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun node(vararg entries: Pair<Int, JsonElement>): JsonArray {
    val values = MutableList<JsonElement>(entries.maxOfOrNull { it.first }?.plus(1) ?: 0) { JsonNull }
    entries.forEach { (index, value) -> values[index] = value }
    return JsonArray(values)
}

class GooglePlaySearchTest {
    @Test
    fun `parses lightweight search results and continuation token`() {
        val item = node(
            0 to node(0 to JsonPrimitive("com.example.cloud")),
            1 to node(3 to node(2 to JsonPrimitive("https://example/icon.png"))),
            3 to JsonPrimitive("Cloud Files"),
            4 to node(0 to JsonPrimitive("4.8"), 1 to JsonPrimitive(4.8)),
            8 to node(1 to node(0 to node(0 to JsonPrimitive(0), 1 to JsonPrimitive("EUR")))),
            13 to node(1 to JsonPrimitive("Sync files everywhere")),
            14 to JsonPrimitive("Example Developer"),
            10 to node(4 to node(2 to JsonPrimitive("/store/apps/details?id=com.example.cloud"))),
        )
        val root = node(
            0 to node(
                1 to node(
                    22 to node(
                        0 to JsonArray(listOf(item)),
                        1 to node(3 to node(1 to JsonPrimitive("next-token"))),
                    ),
                ),
            ),
        )

        val parsed = parseInitialSearchPage(mapOf("ds:4" to root))

        assertEquals(1, parsed.results.size)
        assertEquals("com.example.cloud", parsed.results.single().appId)
        assertEquals("Cloud Files", parsed.results.single().title)
        assertEquals(1, parsed.results.single().rank)
        assertEquals("next-token", parsed.continuationToken)
        assertTrue(parsed.datasetPresent)
    }

    @Test
    fun `missing ds4 is reported as malformed input`() {
        val parsed = parseInitialSearchPage(emptyMap())
        assertTrue(!parsed.datasetPresent)
        assertTrue(parsed.results.isEmpty())
    }

    @Test
    fun `degraded parser preserves ordered package links and is explicitly marked`() {
        val parsed = parseDegradedSearchPage(
            "<a href=\"/store/apps/details?id=com.example.cloud\">Cloud</a>" +
                "<a href=\"https://play.google.com/store/apps/details?id=com.example.ai\">AI</a>" +
                "<a href=\"/store/apps/details?id=com.example.cloud\">duplicate</a>"
        )

        assertEquals(GooglePlaySearchParserMode.DEGRADED_LINK_SCAN, parsed.parserMode)
        assertEquals(listOf("com.example.cloud", "com.example.ai"), parsed.results.map { it.appId })
        assertEquals(listOf(1, 2), parsed.results.map { it.rank })
    }
}
