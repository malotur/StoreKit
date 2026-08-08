package io.github.kdroidfilter.storekit.gplay.core.model

import kotlinx.serialization.Serializable

/**
 * Lightweight result returned by a Google Play keyword search.
 *
 * This is intentionally separate from [GooglePlayApplicationInfo]: search results are cheap,
 * partial records and must not imply that the detail page has been fetched.
 */
@Serializable
data class GooglePlaySearchResult(
    val appId: String = "",
    val title: String = "",
    val url: String = "",
    val icon: String = "",
    val developer: String = "",
    val summary: String = "",
    val score: Double = 0.0,
    val scoreText: String = "",
    val price: Double = 0.0,
    val currency: String = "",
    val free: Boolean = true,
    val rank: Int = 0,
)
