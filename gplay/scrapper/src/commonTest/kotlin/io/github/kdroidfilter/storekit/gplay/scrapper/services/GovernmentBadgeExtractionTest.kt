package io.github.kdroidfilter.storekit.gplay.scrapper.services

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernmentBadgeExtractionTest {
    @Test
    fun `detects the public authority badge regardless of the localized label`() {
        // Markup captured from the Italian store page of a public-authority app: the visible text
        // is translated, the accessibility label is not.
        val italian =
            """<div class="g1rdde"><span aria-label="Government App"><span>Pubblica amministrazione</span></span></div>"""
        val french =
            """<div class="g1rdde"><span aria-label="Government App"><span>Administration publique</span></span></div>"""

        assertTrue(extractGovernmentBadge(italian))
        assertTrue(extractGovernmentBadge(french))
    }

    @Test
    fun `accepts single quoted attributes`() {
        assertTrue(extractGovernmentBadge("<span aria-label='Government App'><span>Government</span></span>"))
    }

    @Test
    fun `does not fire on ordinary applications`() {
        // The word "Government" on its own is not enough: it appears in descriptions of apps that
        // are not published by a public authority.
        val ordinary =
            """<div class="g1rdde"><span>Track government bonds and public spending</span></div>"""

        assertFalse(extractGovernmentBadge(ordinary))
        assertFalse(extractGovernmentBadge(""))
    }
}
