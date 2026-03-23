package com.example.vlesschecker

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class RemoteListLoadResult(
    val text: String,
    val usedCache: Boolean,
    val warning: String?
)

object RemoteListRepository {

    private const val AVAILABLE_URL = "https://whiteprime.github.io/xraycheck/configs/available_st(top100)"
    private const val WHITE_URL = "https://whiteprime.github.io/xraycheck/configs/white-list_available_st(top100)"

    suspend fun loadForSource(
        context: Context,
        source: ListSource,
        forceRefresh: Boolean
    ): RemoteListLoadResult = withContext(Dispatchers.IO) {
        require(source.isRemote) { "Remote source required" }

        val cached = AppPrefs.getRemoteCache(context, source)
        if (!forceRefresh && cached.isNotBlank()) {
            return@withContext RemoteListLoadResult(
                text = cached,
                usedCache = true,
                warning = null
            )
        }

        return@withContext try {
            val fetched = fetchText(urlFor(source))
            AppPrefs.setRemoteCache(context, source, fetched)
            AppPrefs.setRemoteCacheTimestamp(context, source, System.currentTimeMillis())
            RemoteListLoadResult(
                text = fetched,
                usedCache = false,
                warning = null
            )
        } catch (e: Exception) {
            if (cached.isNotBlank()) {
                RemoteListLoadResult(
                    text = cached,
                    usedCache = true,
                    warning = e.message ?: "Не удалось обновить удалённый список"
                )
            } else {
                throw e
            }
        }
    }

    private fun urlFor(source: ListSource): String {
        return when (source) {
            ListSource.REMOTE_AVAILABLE -> AVAILABLE_URL
            ListSource.REMOTE_WHITE -> WHITE_URL
            ListSource.LOCAL_MANUAL -> error("Local source has no remote URL")
        }
    }

    private fun fetchText(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "text/plain,text/*,*/*")
            setRequestProperty("User-Agent", "VlessCheckerAndroid/1.3")
        }

        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
