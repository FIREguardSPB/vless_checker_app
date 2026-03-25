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
    val warningMessage: String? = null,
    val configs: List<VlessChecker.ConfigWithMetadata> = emptyList()
) {
    val links: List<String>
        get() = configs.map { it.link }.ifEmpty { VlessChecker.normalizeLines(rawText) }
}

object RemoteListRepository {
    private const val CONNECT_TIMEOUT_MS = 4000  // Reduced per user requirement
    private const val READ_TIMEOUT_MS = 6000     // Reduced per user requirement

    fun loadForSource(
        context: Context,
        source: ListSource,
        preferFreshRemote: Boolean = true
    ): SourceTextResult {
        return when (source) {
            is ListSource.Manual -> {
                val text = AppPrefs.getServerList(context)
                val configs = VlessChecker.parseLinesWithMetadata(text)
                ConfigFileStore.saveCurrentSnapshot(context, source, text)
                SourceTextResult(
                    source = source,
                    rawText = text,
                    sourceLabel = source.displayName(context),
                    fetchedFresh = false,
                    fromCache = false,
                    configs = configs
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
            val cachedConfigs = VlessChecker.parseLinesWithMetadata(cached)
            return SourceTextResult(
                source = source,
                rawText = cached,
                sourceLabel = sourceLabel,
                fetchedFresh = false,
                fromCache = true,
                configs = cachedConfigs
            )
        }

        val (primaryUrl, fallbackUrl) = urlsForSource(context, source)
        return try {
            val freshText = fetchRemoteList(primaryUrl, fallbackUrl)
            val freshConfigs = VlessChecker.parseLinesWithMetadata(freshText)
            if (freshConfigs.isEmpty()) {
                throw IOException("Список пуст")
            }
            AppPrefs.setRemoteCache(context, source, freshText)
            ConfigFileStore.saveCurrentSnapshot(context, source, freshText)
            SourceTextResult(
                source = source,
                rawText = freshText,
                sourceLabel = sourceLabel,
                fetchedFresh = true,
                fromCache = false,
                configs = freshConfigs
            )
        } catch (e: Exception) {
            if (cached.isNotBlank()) {
                val cachedConfigs = VlessChecker.parseLinesWithMetadata(cached)
                ConfigFileStore.saveCurrentSnapshot(context, source, cached)
                SourceTextResult(
                    source = source,
                    rawText = cached,
                    sourceLabel = sourceLabel,
                    fetchedFresh = false,
                    fromCache = true,
                    warningMessage = e.message ?: "Не удалось обновить список",
                    configs = cachedConfigs
                )
            } else {
                throw e
            }
        }
    }

    private fun urlsForSource(context: Context, source: ListSource): Pair<String, String> {
        return when (source) {
            is ListSource.XrayAvailable -> Pair(
                "https://whiteprime.github.io/xraycheck/configs/available(top100)",
                "https://raw.githubusercontent.com/WhitePrime/xraycheck/main/configs/available%28top100%29"
            )
            is ListSource.XrayWhitelist -> Pair(
                "https://whiteprime.github.io/xraycheck/configs/white-list_available(top100)",
                "https://raw.githubusercontent.com/WhitePrime/xraycheck/main/configs/white-list_available%28top100%29"
            )
            is ListSource.UserDefined -> {
                if (source.url.isBlank()) {
                    error("Пользовательский URL не настроен. Нажмите и удерживайте для добавления.")
                }
                // Convert GitHub page URL to raw URL automatically
                val primaryUrl = source.url
                val fallbackUrl = convertToRawGitHubUrl(primaryUrl)
                Pair(primaryUrl, fallbackUrl)
            }
            is ListSource.Manual -> error("Manual source does not have remote URL")
        }
    }

    /**
     * Convert GitHub page URL (https://github.com/.../blob/...) to raw URL.
     * If not a GitHub blob URL, returns the original URL.
     */
    private fun convertToRawGitHubUrl(url: String): String {
        if (!url.contains("github.com")) return url
        
        return try {
            val regex = """https?://github\.com/([^/]+)/([^/]+)/blob/(.+)""".toRegex()
            val match = regex.find(url)
            if (match != null) {
                val (user, repo, path) = match.destructured
                "https://raw.githubusercontent.com/$user/$repo/$path"
            } else {
                url
            }
        } catch (e: Exception) {
            url
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
