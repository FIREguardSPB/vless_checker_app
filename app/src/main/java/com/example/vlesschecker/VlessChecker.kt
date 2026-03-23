package com.example.vlesschecker

import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

data class CheckResult(
    val link: String,
    val host: String,
    val port: Int,
    val checkType: String
)

data class BatchCheckResult(
    val working: List<CheckResult>,
    val failed: List<String>,
    val skipped: List<String>
)

object VlessChecker {

    fun findFirstAvailable(
        links: List<String>,
        progress: (checked: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): CheckResult? {
        val total = links.size
        links.forEachIndexed { index, rawLink ->
            progress(index + 1, total, rawLink)
            val result = checkSingle(rawLink)
            if (result != null) {
                return result
            }
        }
        return null
    }

    fun checkAll(
        links: List<String>,
        progress: (checked: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): BatchCheckResult {
        val total = links.size
        val working = mutableListOf<CheckResult>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()

        links.forEachIndexed { index, rawLink ->
            progress(index + 1, total, rawLink)
            val parsed = parse(rawLink)
            if (parsed == null) {
                skipped += rawLink
                return@forEachIndexed
            }
            val result = checkParsed(rawLink, parsed)
            if (result != null) {
                working += result
            } else {
                failed += rawLink
            }
        }

        return BatchCheckResult(
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

    private fun checkSingle(rawLink: String): CheckResult? {
        val parsed = parse(rawLink) ?: return null
        return checkParsed(rawLink, parsed)
    }

    private fun checkParsed(rawLink: String, parsed: ParsedVless): CheckResult? {
        val isAvailable = when (parsed.security.lowercase()) {
            "tls" -> testTls(parsed.host, parsed.port, parsed.sni)
            "reality" -> testTcp(parsed.host, parsed.port)
            else -> testTcp(parsed.host, parsed.port)
        }

        if (!isAvailable) return null

        val checkType = when (parsed.security.lowercase()) {
            "tls" -> "TCP + TLS handshake"
            "reality" -> "TCP connect (Reality приблизительно)"
            else -> "TCP connect"
        }

        return CheckResult(
            link = rawLink,
            host = parsed.host,
            port = parsed.port,
            checkType = checkType
        )
    }

    private fun parse(rawLink: String): ParsedVless? {
        return try {
            val clean = rawLink.trim().substringBefore('#')
            val uri = URI(clean)
            if (uri.scheme?.lowercase() != "vless") return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else return null
            val params = parseQuery(uri.rawQuery)
            ParsedVless(
                host = host,
                port = port,
                security = params["security"].orEmpty(),
                sni = params["sni"]
            )
        } catch (_: Exception) {
            null
        }
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

    private fun testTcp(host: String, port: Int, timeoutMs: Int = 2500): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun testTls(host: String, port: Int, sni: String?, timeoutMs: Int = 3500): Boolean {
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
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private data class ParsedVless(
        val host: String,
        val port: Int,
        val security: String,
        val sni: String?
    )
}
