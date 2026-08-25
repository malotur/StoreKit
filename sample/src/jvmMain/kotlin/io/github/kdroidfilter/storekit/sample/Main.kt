package io.github.kdroidfilter.storekit.sample

import io.github.kdroidfilter.storekit.gplay.scrapper.services.getGooglePlayApplicationInfo
import io.github.kdroidfilter.storekit.apklinkresolver.core.service.ApkSourcePriority
import io.github.kdroidfilter.storekit.apklinkresolver.core.service.ApkLinkResolverService
import io.github.kdroidfilter.storekit.apklinkresolver.core.service.ApkSource

import io.github.kdroidfilter.storekit.aptoide.api.services.AptoideService
import io.github.kdroidfilter.storekit.fdroid.api.services.FDroidService
import io.github.kdroidfilter.storekit.apkpure.scraper.services.getApkPureApplicationInfo
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import io.github.kdroidfilter.storekit.gplay.scrapper.services.searchGooglePlay
import io.github.kdroidfilter.storekit.gplay.scrapper.services.GooglePlaySearchOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Sample application that creates example instances of Aptoide, F-Droid, Google Play, APKPure, and APK Downloader models
 * and prints them as JSON.
 */
fun main(args: Array<String>) {

    // Claude Code (claude-opus-5) — modalita' batch di **ricerca**, il complemento della batch per
    // pacchetto che segue. Serve alla misura di richiamo delle keyword del catalogo JINA
    // (JINA_KEYWORD_RECALL_TEST.md): fin qui quella misura si faceva a mano, e una parola non si
    // spedisce senza misurarla. Uso: `--search LANG COUNTRY MAXRESULTS TERM...`; ogni riga stdout
    // e' `{"term":...,"lang":...,"country":...,"results":[{appId,rank,title}...]}`.
    if (args.firstOrNull() == "--search" && args.size >= 5) {
        val lang = args[1]
        val country = args[2]
        val maxResults = args[3].toIntOrNull() ?: 20
        val json = Json { encodeDefaults = true }
        runBlocking {
            args.drop(4).forEach { term ->
                val outcome = searchGooglePlay(term, lang, country, maxResults)
                val results = when (outcome) {
                    is GooglePlaySearchOutcome.Success -> outcome.response.results
                    is GooglePlaySearchOutcome.Partial -> outcome.response.results
                    is GooglePlaySearchOutcome.Failure -> {
                        System.err.println("STOREKIT_SEARCH_ERROR\t$term\t${outcome.error}")
                        emptyList()
                    }
                }
                val payload = buildJsonObject {
                    put("term", JsonPrimitive(term))
                    put("lang", JsonPrimitive(lang))
                    put("country", JsonPrimitive(country))
                    put("results", buildJsonArray {
                        results.forEach { result ->
                            add(buildJsonObject {
                                put("appId", JsonPrimitive(result.appId))
                                put("rank", JsonPrimitive(result.rank))
                                put("title", JsonPrimitive(result.title))
                            })
                        }
                    })
                }
                println(json.encodeToString(JsonObject.serializer(), payload))
            }
        }
        return
    }

    // Batch discovery from a TSV file. Each non-empty line is:
    // queryId<TAB>LANG<TAB>COUNTRY<TAB>MAX_RESULTS<TAB>TERM
    // This keeps terms containing spaces intact when Gradle invokes the sample.
    // It is discovery-only: only appId/rank/title are emitted, never descriptions or labels.
    if (args.firstOrNull() == "--search-file" && args.size >= 2) {
        val json = Json { encodeDefaults = true }
        val lines = Files.readAllLines(Paths.get(args[1]), StandardCharsets.UTF_8)
        val outputArgument = args.firstOrNull { it.startsWith("--output=") }
        val writer = outputArgument?.let {
            val output = Paths.get(it.substringAfter("="))
            output.parent?.let(Files::createDirectories)
            Files.newBufferedWriter(output, StandardCharsets.UTF_8)
        }
        try {
            runBlocking {
                lines.asSequence()
                    .map(String::trimEnd)
                    .filter(String::isNotBlank)
                    .forEach { line ->
                    val fields = line.split('\t', limit = 5)
                    require(fields.size == 5) { "Invalid search TSV line: $line" }
                    val queryId = fields[0]
                    val lang = fields[1]
                    val country = fields[2]
                    val maxResults = fields[3].toIntOrNull() ?: 30
                    val term = fields[4]
                    val outcome = searchGooglePlay(term, lang, country, maxResults)
                    val results = when (outcome) {
                        is GooglePlaySearchOutcome.Success -> outcome.response.results
                        is GooglePlaySearchOutcome.Partial -> outcome.response.results
                        is GooglePlaySearchOutcome.Failure -> {
                            System.err.println("STOREKIT_SEARCH_ERROR\t$queryId\t$term\t${outcome.error}")
                            emptyList()
                        }
                    }
                    val payload = buildJsonObject {
                        put("queryId", JsonPrimitive(queryId))
                        put("term", JsonPrimitive(term))
                        put("lang", JsonPrimitive(lang))
                        put("country", JsonPrimitive(country))
                        put("results", buildJsonArray {
                            results.forEach { result ->
                                add(buildJsonObject {
                                    put("appId", JsonPrimitive(result.appId))
                                    put("rank", JsonPrimitive(result.rank))
                                    put("title", JsonPrimitive(result.title))
                                })
                            }
                        })
                    }
                        val encoded = json.encodeToString(JsonObject.serializer(), payload)
                        if (writer != null) writer.appendLine(encoded) else println(encoded)
                    }
            }
        } finally {
            writer?.close()
        }
        return
    }

    // Modalita' batch minimale per tooling e fixture: LANG COUNTRY PACKAGE...
    // Ogni riga stdout e' un GooglePlayApplicationInfo JSON completo. Senza argomenti il sample
    // storico continua a mostrare tutti i provider.
    if (args.size >= 3) {
        val lang = args[0]
        val country = args[1]
        val outputArgument = args.firstOrNull { it.startsWith("--output=") }
        val packages = args.drop(2).filterNot { it.startsWith("--output=") }
        val json = Json { encodeDefaults = true }
        val lines = runBlocking {
            packages.mapNotNull { packageName ->
                runCatching {
                    json.encodeToString(getGooglePlayApplicationInfo(packageName, lang, country))
                }.onFailure { error ->
                    System.err.println("STOREKIT_BATCH_ERROR\t$packageName\t${error.message}")
                }.getOrNull()
            }
        }
        if (outputArgument != null) {
            val output = Paths.get(outputArgument.substringAfter("="))
            output.parent?.let(Files::createDirectories)
            Files.newBufferedWriter(output, StandardCharsets.UTF_8).use { writer ->
                lines.forEach { line -> writer.appendLine(line) }
            }
        } else {
            lines.forEach(::println)
        }
        return
    }

    ApkSourcePriority.setPriorityOrder(
        listOf(
            ApkSource.APKPURE,
            ApkSource.APKCOMBO,
            ApkSource.FDROID,
            ApkSource.APTOIDE,
        )
    )

    // Create a pretty-printed JSON formatter
    val json = Json { 
        prettyPrint = true 
        encodeDefaults = true
    }
    runBlocking {

        // Create an example Google Play application info
        val gplayApp = getGooglePlayApplicationInfo("com.waze")

        val aptoideService = AptoideService()

        // Create an example Aptoide application info
        val aptoideApp = aptoideService.getAppMetaByPackageName("com.waze")

        // Print the Google Play example as JSON
        println("=== Google Play Example ===")
        println(json.encodeToString(gplayApp))
        println()

        // Print the Aptoide example as JSON
        println("=== Aptoide Example ===")
        println(json.encodeToString(aptoideApp))
        println()

        val fdroidService = FDroidService()

        // Create an example F-Droid package info
        val fdroidPackage = fdroidService.getPackageInfo("net.thunderbird.android")

        // Print the F-Droid example as JSON
        println("=== F-Droid Example ===")
        println(json.encodeToString(fdroidPackage))
        println()

        // APKPure example
        try {
            val apkpureApp = getApkPureApplicationInfo("com.citycar.flutter")
            println("=== APKPure Example ===")
            println(json.encodeToString(apkpureApp))
            println()
        } catch (e: Exception) {
            println("Error retrieving APKPure info: ${e.message}")
        }

        // APK Downloader example
        println("=== APK Downloader Example ===")

        // Create an instance of the APK Downloader service
        val apkLinkResolverService = ApkLinkResolverService()

        try {
            // Get download link for a package using the custom priority
            val downloadInfo = apkLinkResolverService.getApkDownloadLink("com.unicell.pangoandroid")

            println("Download info for com.apple.bnd:")
            println(json.encodeToString(downloadInfo))
            println()
            println("Source: ${downloadInfo.source}")
            println("Title: ${downloadInfo.title}")
            println("Version: ${downloadInfo.version}")
            println("Version Code: ${downloadInfo.versionCode}")
            println("Download Link: ${downloadInfo.downloadLink}")
            println("File Size: ${downloadInfo.fileSize} bytes")
        } catch (e: Exception) {
            println("Error retrieving download link: ${e.message}")
        } finally {
            // Reset to default priority
            ApkSourcePriority.resetToDefault()
        }
    }
}
