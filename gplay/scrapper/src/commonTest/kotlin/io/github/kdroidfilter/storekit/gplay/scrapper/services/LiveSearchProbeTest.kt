package io.github.kdroidfilter.storekit.gplay.scrapper.services

import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Sonda dal vivo contro Google Play. Claude Code (claude-opus-5) — punto #5 di Codex: senza una
 * verifica sull'HTML vero il parser passava i test sintetici e restituiva zero risultati sul campo.
 */
class LiveSearchProbeTest {
    @Test fun probe(): Unit = runBlocking {
        listOf(Triple("cloud storage", "it", "it"), Triple("vpn", "en", "us")).forEach { (q, l, c) ->
            when (val outcome = searchGooglePlay(q, l, c, maxResults = 30)) {
                is GooglePlaySearchOutcome.Success -> println(
                    "PROBE $q $l/$c -> ${outcome.response.results.size} risultati " +
                        "mode=${outcome.response.parserMode} " +
                        "primi=${outcome.response.results.take(3).map { "${it.rank}:${it.appId}:${it.title}" }}")
                is GooglePlaySearchOutcome.Partial -> println("PROBE $q -> PARZIALE ${outcome.response.results.size}")
                is GooglePlaySearchOutcome.Failure -> println("PROBE $q -> FALLITO ${outcome.error}")
            }
        }
    }
}
