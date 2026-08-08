package io.github.kdroidfilter.storekit.gplay.scrapper.services

import io.github.kdroidfilter.storekit.gplay.core.model.GooglePlaySearchResult
import io.github.kdroidfilter.storekit.gplay.scrapper.constants.BASE_PLAY_STORE_URL
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.JsonExtensions.asDoubleOrNull
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.JsonExtensions.asLongOrNull
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.JsonExtensions.asStringOrNull
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.JsonExtensions.microsToPrice
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.JsonExtensions.nestedLookup
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.NetworkUtils.extractJsonBlobsFromHtml
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.NetworkUtils.buildSearchClusterBody
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.NetworkUtils.fetchSearchClusterPage
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.NetworkUtils.fetchSearchPage
import io.github.kdroidfilter.storekit.gplay.scrapper.utils.parseDataSetsFromScripts
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlin.coroutines.cancellation.CancellationException

private const val MAX_SEARCH_RESULTS = 250
private const val SEARCH_PARSER_VERSION = "search-ds4-v1"
private const val CLUSTER_PAGE_SIZE = 100

/** Indicates how much structure was recovered from the Play response. */
enum class GooglePlaySearchParserMode {
    STRUCTURED_DS4,
    DEGRADED_LINK_SCAN,
}

/** Structured failures exposed to the caller; response HTML is deliberately not included. */
sealed interface GooglePlaySearchError {
    data class InvalidRequest(val reason: String) : GooglePlaySearchError
    data class HttpFailure(val statusCode: Int, val retryAfterSeconds: Long? = null) : GooglePlaySearchError
    data class NetworkUnavailable(val reason: String) : GooglePlaySearchError
    data class RateLimited(val retryAfterSeconds: Long? = null) : GooglePlaySearchError
    data class BlockedOrConsentRequired(val reason: String) : GooglePlaySearchError
    data class MalformedSearchPage(val parserVersion: String = SEARCH_PARSER_VERSION) : GooglePlaySearchError
    data object Cancelled : GooglePlaySearchError
}

data class GooglePlaySearchResponse(
    val results: List<GooglePlaySearchResult>,
    val requestedMaxResults: Int,
    val exhausted: Boolean,
    val continuationAvailable: Boolean,
    val parserMode: GooglePlaySearchParserMode = GooglePlaySearchParserMode.STRUCTURED_DS4,
)

sealed interface GooglePlaySearchOutcome {
    data class Success(val response: GooglePlaySearchResponse) : GooglePlaySearchOutcome
    data class Partial(
        val response: GooglePlaySearchResponse,
        val error: GooglePlaySearchError,
    ) : GooglePlaySearchOutcome
    data class Failure(val error: GooglePlaySearchError) : GooglePlaySearchOutcome
}

internal data class ParsedSearchPage(
    val results: List<GooglePlaySearchResult>,
    val continuationToken: String?,
    val datasetPresent: Boolean,
    val parserMode: GooglePlaySearchParserMode = GooglePlaySearchParserMode.STRUCTURED_DS4,
)

private fun JsonElement?.asSearchNumber(): Double? = when (this) {
    is JsonPrimitive -> asDoubleOrNull() ?: asLongOrNull()?.toDouble()
    else -> null
}

private fun resolvePlayUrl(value: String): String = when {
    value.startsWith("http://") || value.startsWith("https://") -> value
    value.startsWith("/") -> "$BASE_PLAY_STORE_URL$value"
    value.isBlank() -> ""
    else -> "$BASE_PLAY_STORE_URL/$value"
}

private fun parseSearchItem(element: JsonElement, rank: Int): GooglePlaySearchResult? {
    // Claude Code (claude-opus-5) — Google avvolge ogni record in un contenitore di un solo
    // elemento, e a volte in piu' di uno. I percorsi qui sotto sono corretti **rispetto al
    // record**: senza scartare l'involucro `appId` risultava sempre nullo e ogni risultato veniva
    // scartato, producendo un successo con zero elementi — indistinguibile da «nessun risultato».
    var item = element
    while (item is JsonArray && item.size == 1) item = item[0]
    val appId = nestedLookup(item, listOf(0, 0)).asStringOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val priceElement = nestedLookup(item, listOf(8, 1, 0, 0))
    return GooglePlaySearchResult(
        appId = appId,
        title = nestedLookup(item, listOf(3)).asStringOrNull().orEmpty(),
        url = resolvePlayUrl(nestedLookup(item, listOf(10, 4, 2)).asStringOrNull().orEmpty()),
        icon = nestedLookup(item, listOf(1, 3, 2)).asStringOrNull().orEmpty(),
        developer = nestedLookup(item, listOf(14)).asStringOrNull().orEmpty(),
        summary = nestedLookup(item, listOf(13, 1)).asStringOrNull().orEmpty(),
        score = nestedLookup(item, listOf(4, 1)).asSearchNumber() ?: 0.0,
        scoreText = nestedLookup(item, listOf(4, 0)).asStringOrNull().orEmpty(),
        price = microsToPrice(priceElement),
        currency = nestedLookup(item, listOf(8, 1, 0, 1)).asStringOrNull().orEmpty(),
        free = (priceElement.asLongOrNull() ?: priceElement.asSearchNumber()?.toLong() ?: 0L) == 0L,
        rank = rank,
    )
}

internal fun parseInitialSearchPage(datasets: Map<String, JsonElement>): ParsedSearchPage {
    val searchData = datasets["ds:4"] ?: return ParsedSearchPage(emptyList(), null, false)
    val sections = nestedLookup(searchData, listOf(0, 1)) as? JsonArray
        ?: return ParsedSearchPage(emptyList(), null, true)
    // Claude Code (claude-opus-5) — la sezione che contiene il cluster di app **non ha un indice
    // fisso**: misurato sulle risposte reali, e' `[0][1][1][22][0]` per it/IT e `[0][1][0][22][0]`
    // per en/US, perche' Google antepone sezioni diverse a seconda della risposta. Fissare
    // l'indice produceva un successo con zero risultati, indistinguibile da «nessun risultato».
    // Si scandiscono quindi le sezioni e si prende la prima che espone una lista non vuota.
    var apps: JsonArray? = null
    var token: String? = null
    for (section in sections) {
        val candidate = nestedLookup(section, listOf(22, 0)) as? JsonArray ?: continue
        if (candidate.isEmpty()) continue
        apps = candidate
        token = nestedLookup(section, listOf(22, 1, 3, 1)).asStringOrNull()
        break
    }
    if (apps == null) return ParsedSearchPage(emptyList(), null, true)
    val results = apps.mapIndexedNotNull { index, item -> parseSearchItem(item, index + 1) }
        .distinctBy { it.appId }
    return ParsedSearchPage(results, token, true, GooglePlaySearchParserMode.STRUCTURED_DS4)
}

/**
 * Last-resort parser for build-time review only. Google sometimes serves links without the ds:4
 * dataset; this deliberately returns only package id and rank, and marks the result degraded so a
 * runtime classifier can reject it rather than silently treating contaminated HTML as ranked data.
 */
internal fun parseDegradedSearchPage(body: String): ParsedSearchPage {
    val pattern = Regex("(?:[?&]id=|/store/apps/details\\?id=)([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)")
    val ids = pattern.findAll(body).map { it.groupValues[1] }.distinct().toList()
    val results = ids.mapIndexed { index, id ->
        GooglePlaySearchResult(
            appId = id,
            url = "$BASE_PLAY_STORE_URL/store/apps/details?id=$id",
            rank = index + 1,
        )
    }
    return ParsedSearchPage(
        results = results,
        continuationToken = null,
        datasetPresent = results.isNotEmpty(),
        parserMode = GooglePlaySearchParserMode.DEGRADED_LINK_SCAN,
    )
}

private fun parseClusterResponse(body: String): ParsedSearchPage {
    val start = body.indexOf('[')
    if (start < 0) return ParsedSearchPage(emptyList(), null, false)
    val candidates = body.substring(start).split('\n').asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line -> runCatching { Json.parseToJsonElement(line) }.getOrNull() }
        .toList()

    fun payloadFromFrames(frames: JsonArray): JsonElement? = frames.firstNotNullOfOrNull { frame ->
        val entry = frame as? JsonArray ?: return@firstNotNullOfOrNull null
        if (entry.getOrNull(0).asStringOrNull() != "wrb.fr" ||
            entry.getOrNull(1).asStringOrNull() != io.github.kdroidfilter.storekit.gplay.scrapper.constants.SEARCH_CLUSTER_RPC_ID
        ) return@firstNotNullOfOrNull null
        entry.getOrNull(2).asStringOrNull()?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
    }

    val payload = candidates.asSequence()
        .mapNotNull { it as? JsonArray }
        .mapNotNull(::payloadFromFrames)
        .firstOrNull()
        ?: return ParsedSearchPage(emptyList(), null, false)
    val apps = nestedLookup(payload, listOf(0, 0, 0)) as? JsonArray
        ?: return ParsedSearchPage(emptyList(), null, true)
    val token = nestedLookup(payload, listOf(0, 0, 7, 1)).asStringOrNull()
    return ParsedSearchPage(
        results = apps.mapIndexedNotNull { index, item -> parseSearchItem(item, index + 1) }
            .distinctBy { it.appId },
        continuationToken = token,
        datasetPresent = true,
    )
}

/**
 * Executes a lightweight Play Store keyword search.
 *
 * StoreKit performs one page fetch and exposes whether Google returned a continuation token. Retry,
 * cache and scheduling policies belong to the caller (JINA), not to this library.
 */
suspend fun searchGooglePlay(
    term: String,
    lang: String = "en",
    country: String = "us",
    maxResults: Int = 20,
    price: GooglePlaySearchPrice = GooglePlaySearchPrice.ALL,
): GooglePlaySearchOutcome {
    val normalizedTerm = term.trim()
    if (normalizedTerm.isBlank()) return GooglePlaySearchOutcome.Failure(
        GooglePlaySearchError.InvalidRequest("term must not be blank"),
    )
    if (maxResults !in 1..MAX_SEARCH_RESULTS) return GooglePlaySearchOutcome.Failure(
        GooglePlaySearchError.InvalidRequest("maxResults must be between 1 and $MAX_SEARCH_RESULTS"),
    )

    val response = try {
        fetchSearchPage(normalizedTerm, lang, country, price.googleValue)
    } catch (cancelled: CancellationException) {
        return GooglePlaySearchOutcome.Failure(GooglePlaySearchError.Cancelled)
    } catch (error: Throwable) {
        return GooglePlaySearchOutcome.Failure(
            GooglePlaySearchError.NetworkUnavailable(error.message ?: error::class.simpleName.orEmpty()),
        )
    }

    val body = try {
        response.bodyAsText()
    } catch (error: Throwable) {
        return GooglePlaySearchOutcome.Failure(
            GooglePlaySearchError.NetworkUnavailable(error.message ?: "Unable to read response"),
        )
    }
    val retryAfter = response.headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }
    val status = response.status
    if (status == HttpStatusCode.TooManyRequests) {
        return GooglePlaySearchOutcome.Failure(GooglePlaySearchError.RateLimited(retryAfter))
    }
    if (status.value !in 200..299) {
        return GooglePlaySearchOutcome.Failure(GooglePlaySearchError.HttpFailure(status.value, retryAfter))
    }
    if (body.contains("recaptcha", ignoreCase = true) ||
        body.contains("unusual traffic", ignoreCase = true) ||
        body.contains("consent.google.com", ignoreCase = true)
    ) {
        return GooglePlaySearchOutcome.Failure(
            GooglePlaySearchError.BlockedOrConsentRequired("Google Play returned a consent or anti-bot page"),
        )
    }

    val datasets = try {
        parseDataSetsFromScripts(extractJsonBlobsFromHtml(body))
    } catch (_: Throwable) {
        emptyMap()
    }
    val parsed = parseInitialSearchPage(datasets).let { structured ->
        if (structured.datasetPresent) structured else parseDegradedSearchPage(body)
    }
    if (!parsed.datasetPresent) return GooglePlaySearchOutcome.Failure(
        GooglePlaySearchError.MalformedSearchPage(),
    )

    val results = parsed.results.toMutableList()
    val parserMode = parsed.parserMode
    val seenTokens = mutableSetOf<String>()
    var continuationToken = parsed.continuationToken
    while (results.size < maxResults && continuationToken != null && seenTokens.add(continuationToken)) {
        val clusterResponse = try {
            fetchSearchClusterPage(
                lang,
                country,
                buildSearchClusterBody(CLUSTER_PAGE_SIZE, continuationToken),
            )
        } catch (cancelled: CancellationException) {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true, parserMode),
                GooglePlaySearchError.Cancelled,
            )
        } catch (error: Throwable) {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true, parserMode),
                GooglePlaySearchError.NetworkUnavailable(error.message ?: error::class.simpleName.orEmpty()),
            )
        }
        val clusterBody = runCatching { clusterResponse.bodyAsText() }.getOrElse {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true, parserMode),
                GooglePlaySearchError.NetworkUnavailable(it.message ?: "Unable to read cluster response"),
            )
        }
        val retryAfterCluster = clusterResponse.headers["Retry-After"]?.toLongOrNull()?.takeIf { it >= 0 }
        if (clusterResponse.status == HttpStatusCode.TooManyRequests) {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true, parserMode),
                GooglePlaySearchError.RateLimited(retryAfterCluster),
            )
        }
        if (clusterResponse.status.value !in 200..299) {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true, parserMode),
                GooglePlaySearchError.HttpFailure(clusterResponse.status.value, retryAfterCluster),
            )
        }
        val clusterPage = parseClusterResponse(clusterBody)
        if (!clusterPage.datasetPresent) {
            return GooglePlaySearchOutcome.Partial(
                GooglePlaySearchResponse(results.take(maxResults), maxResults, false, true),
                GooglePlaySearchError.MalformedSearchPage(),
            )
        }
        clusterPage.results.forEach { result ->
            if (results.none { it.appId == result.appId }) results += result.copy(rank = results.size + 1)
        }
        continuationToken = clusterPage.continuationToken
    }

    val finalResults = results.take(maxResults)
    val result = GooglePlaySearchResponse(
        results = finalResults,
        requestedMaxResults = maxResults,
        exhausted = continuationToken == null || finalResults.size >= maxResults,
        continuationAvailable = continuationToken != null,
        parserMode = parserMode,
    )
    return GooglePlaySearchOutcome.Success(result)
}

enum class GooglePlaySearchPrice(internal val googleValue: Int) {
    ALL(0), FREE(1), PAID(2),
}
