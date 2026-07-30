package io.github.kdroidfilter.storekit.gplay.scrapper.services

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentChangesExtractionTest {
    @Test
    fun `extracts and sanitizes the Play Store changelog`() {
        // Matches the stable path in ds:5: 1 -> 2 -> 144 -> 1 -> 1.
        val root = Json.parseToJsonElement(
            """[null,[null,null,[${"null,".repeat(144)}[null,[null,"Bug\u0000 fixes\nImproved stability"]]]]]""",
        )

        assertEquals("Bug fixes\nImproved stability", extractRecentChanges(root))
    }

    @Test
    fun `falls back to the relocated Play Store changelog field`() {
        val root = Json.parseToJsonElement(
            """[null,[null,null,[${"null,".repeat(144)}null,{"145":[null,[null,"Stability improvement"]]}]]]""",
        )

        assertEquals("Stability improvement", extractRecentChanges(root))
    }
}
