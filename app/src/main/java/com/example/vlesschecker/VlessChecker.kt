package com.example.vlesschecker

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class CheckResult(
    val link: String,
    val host: String,
    val port: Int,
    val checkType: String,
    val latencyMs: Long
)

data class LinkCheckResult(
    val link: String,
    val host: String?,
    val port: Int?,
    val checkType: String,
    val latencyMs: Long?,
    val isWorking: Boolean,
    val statusText: String
)

data class BatchCheckResult(
    val checked: List<LinkCheckResult>,
    val working: List<CheckResult>,
    val failed: List<LinkCheckResult>,
    val skipped: List<LinkCheckResult>
)

object VlessChecker {

    fun findFastestAvailable(
        links: List<String>,
        progress: (checked: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): CheckResult? {
        return checkAll(links, progress).working.minByOrNull { it.latencyMs }
    }

    fun checkAll(
        links: List<String>,
        progress: (checked: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): BatchCheckResult {
        val total = links.size
        val checked = mutableListOf<LinkCheckResult>()

        links.forEachIndexed { index, rawLink ->
            progress(index + 1, total, rawLink)
            checked += checkSingleDetailed(rawLink)
        }

        val working = checked
            .filter { it.isWorking && it.host != null && it.port != null && it.latencyMs != null }
            .map {
                CheckResult(
                    link = it.link,
                    host = it.host!!,
                    port = it.port!!,
                    checkType = it.checkType,
                    latencyMs = it.latencyMs!!
                )
            }
            .sortedBy { it.latencyMs }

        val failed = checked.filter { !it.isWorking && it.host != null }
        val skipped = checked.filter { !it.isWorking && it.host == null }

        return BatchCheckResult(
            checked = checked,
            working = working,
            failed = failed,
            skipped = skipped
        )
    }

    fun normalizeLines(rawText: String): List<String> {
        return rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun checkSingleDetailed(rawLink: String): LinkCheckResult {
        val parsed = parse(rawLink)
            ?: return LinkCheckResult(
                link = rawLink,
                host = null,
                port = null,
                checkType = "Не распознано",
                latencyMs = null,
                isWorking = false,
                statusText = "Пропущено: неподдерживаемая или некорректная строка"
            )

        return checkParsed(rawLink, parsed)
    }

    private fun checkParsed(rawLink: String, parsed: ParsedEndpoint): LinkCheckResult {
        val measured = when (parsed.security.lowercase()) {
            "tls" -> testTls(parsed.host, parsed.port, parsed.sni)
            "reality" -> testTcp(parsed.host, parsed.port)
            else -> testTcp(parsed.host, parsed.port)
        }

        val checkType = when (parsed.security.lowercase()) {
            "tls" -> "${parsed.scheme.uppercase()} · TCP + TLS handshake"
            "reality" -> "${parsed.scheme.uppercase()} · TCP connect (Reality приблизительно)"
            else -> "${parsed.scheme.uppercase()} · TCP connect"
        }

        return if (measured != null) {
            LinkCheckResult(
                link = rawLink,
                host = parsed.host,
                port = parsed.port,
                checkType = checkType,
                latencyMs = measured,
                isWorking = true,
                statusText = "OK — ${measured} мс"
            )
        } else {
            LinkCheckResult(
                link = rawLink,
                host = parsed.host,
                port = parsed.port,
                checkType = checkType,
                latencyMs = null,
                isWorking = false,
                statusText = "Недоступно"
            )
        }
    }

    private fun parse(rawLink: String): ParsedEndpoint? {
        val clean = rawLink.trim().substringBefore('#')
        val scheme = clean.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "vless", "trojan" -> parseStandardUri(clean, scheme)
            "vmess" -> parseVmess(clean)
            else -> null
        }
    }

    private fun parseStandardUri(cleanLink: String, scheme: String): ParsedEndpoint? {
        return try {
            val uri = URI(cleanLink)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else return null
            val params = parseQuery(uri.rawQuery)
            val security = when {
                scheme == "trojan" && params["security"].isNullOrBlank() -> "tls"
                else -> params["security"].orEmpty()
            }
            ParsedEndpoint(
                scheme = scheme,
                host = host,
                port = port,
                security = security,
                sni = params["sni"]
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmess(cleanLink: String): ParsedEndpoint? {
        return try {
            val encoded = cleanLink.removePrefix("vmess://")
            val jsonText = decodeBase64ToString(encoded)
            val json = JSONObject(jsonText)
            val host = json.optString("add").ifBlank { return null }
            val port = json.optString("port").toIntOrNull() ?: return null
            val tlsValue = json.optString("tls")
            val sniValue = json.optString("sni").ifBlank { json.optString("host") }
            ParsedEndpoint(
                scheme = "vmess",
                host = host,
                port = port,
                security = if (tlsValue.equals("tls", ignoreCase = true)) "tls" else "none",
                sni = sniValue.ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64ToString(value: String): String {
        val normalized = value.trim()
            .replace('-', '+')
            .replace('_', '/')
            .let { raw ->
                val padding = (4 - raw.length % 4) % 4
                raw + "=".repeat(padding)
            }
        return String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val pieces = part.split("=", limit = 2)
                if (pieces.isEmpty()) {
                    null
                } else {
                    val key = URLDecoder.decode(pieces[0], Charsets.UTF_8.name())
                    val value = URLDecoder.decode(pieces.getOrElse(1) { "" }, Charsets.UTF_8.name())
                    key to value
                }
            }
            .toMap()
    }

    private fun testTcp(host: String, port: Int, timeoutMs: Int = 2500): Long? {
        val startNs = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                elapsedMs(startNs)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun testTls(host: String, port: Int, sni: String?, timeoutMs: Int = 3500): Long? {
        val startNs = System.nanoTime()
        return try {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, null, null)
            val factory = sslContext.socketFactory
            (factory.createSocket() as SSLSocket).use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                val params = socket.sslParameters
                val serverName = sni ?: host
                params.serverNames = listOf(SNIHostName(serverName))
                socket.sslParameters = params
                socket.startHandshake()
                elapsedMs(startNs)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun elapsedMs(startNs: Long): Long {
        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
    }

    private data class ParsedEndpoint(
        val scheme: String,
        val host: String,
        val port: Int,
        val security: String,
        val sni: String?
    )
}
