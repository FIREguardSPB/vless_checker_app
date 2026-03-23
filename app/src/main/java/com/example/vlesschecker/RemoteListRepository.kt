package com.example.vlesschecker

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class SourceTextResult(
    val source: ListSource,
    val rawText: String,
    val sourceLabel: String,
    val fetchedFresh: Boolean,
    val fromCache: Boolean,
    val warningMessage: String? = null
) {
    val links: List<String>
        get() = VlessChecker.normalizeLines(rawText)
}

object RemoteListRepository {
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000

    fun loadForSource(
        context: Context,
        source: ListSource,
        preferFreshRemote: Boolean = true
    ): SourceTextResult {
        return when (source) {
            ListSource.MANUAL -> {
                val text = AppPrefs.getServerList(context)
                SourceTextResult(
                    source = source,
                    rawText = text,
                    sourceLabel = source.displayName(context),
                    fetchedFresh = false,
                    fromCache = false
                )
            }
            else -> loadRemoteForSource(context, source, preferFreshRemote)
        }
    }

    private fun loadRemoteForSource(
        context: Context,
        source: ListSource,
        preferFreshRemote: Boolean
    ): SourceTextResult {
        val sourceLabel = source.displayName(context)
        val cached = AppPrefs.getRemoteCache(context, source)

        if (!preferFreshRemote && cached.isNotBlank()) {
            return SourceTextResult(
                source = source,
                rawText = cached,
                sourceLabel = sourceLabel,
                fetchedFresh = false,
                fromCache = true
            )
        }

        val (primaryUrl, fallbackUrl) = urlsForSource(source)
        return try {
            val freshText = fetchRemoteList(primaryUrl, fallbackUrl)
            if (VlessChecker.normalizeLines(freshText).isEmpty()) {
                throw IOException("Список пуст")
            }
            AppPrefs.setRemoteCache(context, source, freshText)
            SourceTextResult(
                source = source,
                rawText = freshText,
                sourceLabel = sourceLabel,
                fetchedFresh = true,
                fromCache = false
            )
        } catch (e: Exception) {
            if (cached.isNotBlank()) {
                SourceTextResult(
                    source = source,
                    rawText = cached,
                    sourceLabel = sourceLabel,
                    fetchedFresh = false,
                    fromCache = true,
                    warningMessage = e.message ?: "Не удалось обновить список"
                )
            } else {
                throw e
            }
        }
    }

    private fun urlsForSource(source: ListSource): Pair<String, String> {
        return when (source) {
            ListSource.XRAY_AVAILABLE_ST_TOP100 -> Pair(
                "https://whiteprime.github.io/xraycheck/configs/available(top100)",
                "https://raw.githubusercontent.com/WhitePrime/xraycheck/main/configs/available%28top100%29"
            )
            ListSource.XRAY_WHITE_LIST_ST_TOP100 -> Pair(
                "https://whiteprime.github.io/xraycheck/configs/white-list_available(top100)",
                "https://raw.githubusercontent.com/WhitePrime/xraycheck/main/configs/white-list_available%28top100%29"
            )
            ListSource.MANUAL -> error("Manual source does not have remote URL")
        }
    }

    private fun fetchRemoteList(primaryUrl: String, fallbackUrl: String): String {
        var primaryError: Exception? = null
        try {
            val primaryText = downloadText(primaryUrl)
            if (looksLikePlainList(primaryText)) {
                return primaryText
            }
        } catch (e: Exception) {
            primaryError = e
        }

        val fallbackText = downloadText(fallbackUrl)
        if (looksLikePlainList(fallbackText)) {
            return fallbackText
        }

        throw primaryError ?: IOException("Удалённый список вернул неожиданный формат")
    }

    private fun looksLikePlainList(text: String): Boolean {
        val head = text.trimStart().take(256).lowercase()
        if (head.startsWith("<!doctype html") || head.startsWith("<html")) return false
        return true
    }

    private fun downloadText(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", "VlessChecker/1.4")
            setRequestProperty("Accept", "text/plain, */*")
        }

        return connection.useAndDisconnect { conn ->
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        }
    }

    private inline fun <T> HttpURLConnection.useAndDisconnect(block: (HttpURLConnection) -> T): T {
        return try {
            block(this)
        } finally {
            disconnect()
        }
    }
}
