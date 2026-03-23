package com.example.vlesschecker

import org.json.JSONObject
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

private data class ProbeTarget(
    val host: String,
    val port: Int,
    val request: ByteArray
)

private val PROBE_TARGETS = listOf(
    ProbeTarget(
        host = "example.com",
        port = 80,
        request = (
            "GET / HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "User-Agent: VlessChecker/1.8\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
    ),
    ProbeTarget(
        host = "connectivitycheck.gstatic.com",
        port = 80,
        request = (
            "GET /generate_204 HTTP/1.1\r\n" +
                "Host: connectivitycheck.gstatic.com\r\n" +
                "Connection: close\r\n" +
                "User-Agent: VlessChecker/1.8\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
    )
)

enum class CheckConfidence(val rank: Int) {
    CONFIRMED(0),
    CANDIDATE(1)
}

data class CheckResult(
    val link: String,
    val host: String,
    val port: Int,
    val checkType: String,
    val latencyMs: Long,
    val confidence: CheckConfidence
)

data class LinkCheckResult(
    val link: String,
    val host: String?,
    val port: Int?,
    val checkType: String,
    val latencyMs: Long?,
    val isWorking: Boolean,
    val statusText: String,
    val confidence: CheckConfidence? = null
)

data class BatchCheckResult(
    val checked: List<LinkCheckResult>,
    val working: List<CheckResult>,
    val confirmed: List<CheckResult>,
    val candidates: List<CheckResult>,
    val failed: List<LinkCheckResult>,
    val skipped: List<LinkCheckResult>
)

object VlessChecker {

    fun findFastestAvailable(
        links: List<String>,
        progress: (checked: Int, total: Int, current: String) -> Unit = { _, _, _ -> }
    ): CheckResult? {
        return checkAll(links, progress).working.firstOrNull()
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
            .filter { it.isWorking && it.host != null && it.port != null && it.latencyMs != null && it.confidence != null }
            .map {
                CheckResult(
                    link = it.link,
                    host = it.host!!,
                    port = it.port!!,
                    checkType = it.checkType,
                    latencyMs = it.latencyMs!!,
                    confidence = it.confidence!!
                )
            }
            .sortedWith(compareBy<CheckResult> { it.confidence.rank }.thenBy { it.latencyMs })

        val confirmed = working.filter { it.confidence == CheckConfidence.CONFIRMED }
        val candidates = working.filter { it.confidence == CheckConfidence.CANDIDATE }
        val failed = checked.filter { !it.isWorking && it.host != null }
        val skipped = checked.filter { !it.isWorking && it.host == null }

        return BatchCheckResult(
            checked = checked,
            working = working,
            confirmed = confirmed,
            candidates = candidates,
            failed = failed,
            skipped = skipped
        )
    }

    fun normalizeLines(rawText: String): List<String> {
        return rawText.lineSequence()
            .mapNotNull { canonicalizeSupportedLink(it) }
            .toList()
    }

    fun normalizeLine(rawLine: String): String {
        return rawLine
            .replace("﻿", "")
            .replace("​", "")
            .replace("‌", "")
            .replace("‍", "")
            .replace("⁠", "")
            .replace(" ", " ")
            .replace("\r", "")
            .trim()
            .trim('"', '\'', '`')
    }

    fun extractSupportedLink(rawText: String): String? {
        val normalized = normalizeLine(rawText)
        if (normalized.isBlank()) return null

        val lower = normalized.lowercase()
        val supportedSchemes = listOf("vless://", "vmess://", "trojan://")
        val startIndex = supportedSchemes
            .map { lower.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull()
            ?: return null

        return normalized.substring(startIndex).trim()
    }

    fun canonicalizeSupportedLink(rawText: String): String? {
        val extracted = extractSupportedLink(rawText) ?: return null
        val clean = extracted.substringBefore('#').trim()
        val scheme = clean.substringBefore("://", missingDelimiterValue = "").lowercase()
        return when (scheme) {
            "vmess" -> canonicalizeVmess(clean)
            "vless", "trojan" -> clean
            else -> null
        }
    }

    private fun checkSingleDetailed(rawLink: String): LinkCheckResult {
        val sanitizedLink = canonicalizeSupportedLink(rawLink) ?: normalizeLine(rawLink)
        val parsed = parse(sanitizedLink)
            ?: return LinkCheckResult(
                link = sanitizedLink,
                host = null,
                port = null,
                checkType = "Не распознано",
                latencyMs = null,
                isWorking = false,
                statusText = "Пропущено: неподдерживаемая или некорректная строка"
            )

        return checkParsed(sanitizedLink, parsed)
    }

    private fun checkParsed(rawLink: String, parsed: ParsedEndpoint): LinkCheckResult {
        val protocolLatency = if (canRunProtocolProbe(parsed)) {
            runProtocolProbe(parsed)
        } else {
            null
        }

        if (protocolLatency != null) {
            return LinkCheckResult(
                link = rawLink,
                host = parsed.host,
                port = parsed.port,
                checkType = protocolCheckType(parsed),
                latencyMs = protocolLatency,
                isWorking = true,
                statusText = "Подтверждено протокольной проверкой",
                confidence = CheckConfidence.CONFIRMED
            )
        }

        val reachabilityLatency = testEndpointReachability(parsed)
        if (reachabilityLatency != null) {
            val strictReality = isStrictRealityVisionProfile(parsed)
            return LinkCheckResult(
                link = rawLink,
                host = parsed.host,
                port = parsed.port,
                checkType = if (strictReality) strictRealityCheckType(parsed) else candidateCheckType(parsed),
                latencyMs = reachabilityLatency,
                isWorking = true,
                statusText = if (strictReality) strictRealityReason(parsed) else candidateReason(parsed),
                confidence = if (strictReality) CheckConfidence.CONFIRMED else CheckConfidence.CANDIDATE
            )
        }

        return LinkCheckResult(
            link = rawLink,
            host = parsed.host,
            port = parsed.port,
            checkType = failedCheckType(parsed),
            latencyMs = null,
            isWorking = false,
            statusText = "Endpoint недоступен"
        )
    }

    private fun canRunProtocolProbe(parsed: ParsedEndpoint): Boolean {
        if (parsed.scheme !in setOf("vless", "trojan")) return false
        if (parsed.security.equals("reality", ignoreCase = true)) return false
        if (parsed.flow.contains("xtls-rprx-vision", ignoreCase = true)) return false
        val transport = parsed.transport.lowercase()
        return transport in setOf("", "tcp", "raw")
    }

    private fun protocolCheckType(parsed: ParsedEndpoint): String {
        return when (parsed.scheme) {
            "vless" -> "VLESS · подтверждено протокольной проверкой"
            "trojan" -> "TROJAN · подтверждено протокольной проверкой"
            else -> "${parsed.scheme.uppercase()} · подтверждено"
        }
    }

    private fun candidateCheckType(parsed: ParsedEndpoint): String {
        return when {
            parsed.security.equals("reality", ignoreCase = true) ->
                "${parsed.scheme.uppercase()} · совместимая проверка endpoint (REALITY)"
            parsed.flow.contains("xtls-rprx-vision", ignoreCase = true) ->
                "${parsed.scheme.uppercase()} · совместимая проверка endpoint (Vision)"
            parsed.transport.lowercase() !in setOf("", "tcp", "raw") ->
                "${parsed.scheme.uppercase()} · совместимая проверка endpoint (${parsed.transport})"
            parsed.scheme == "vmess" -> "VMESS · совместимая проверка endpoint"
            else -> "${parsed.scheme.uppercase()} · совместимая проверка endpoint"
        }
    }

    private fun strictRealityCheckType(parsed: ParsedEndpoint): String {
        return "${parsed.scheme.uppercase()} · строгая профильная проверка REALITY/Vision"
    }

    private fun failedCheckType(parsed: ParsedEndpoint): String {
        return when {
            canRunProtocolProbe(parsed) -> protocolCheckType(parsed)
            else -> candidateCheckType(parsed)
        }
    }

    private fun candidateReason(parsed: ParsedEndpoint): String {
        return when {
            parsed.security.equals("reality", ignoreCase = true) ->
                "Кандидат: порт открыт, но для REALITY без Xray-core это еще не гарантия рабочего конфига"
            parsed.flow.contains("xtls-rprx-vision", ignoreCase = true) ->
                "Кандидат: endpoint доступен, но flow=xtls-rprx-vision требует полноценного клиента"
            parsed.scheme == "vmess" ->
                "Кандидат: endpoint доступен, но VMESS в этой версии не проходит полноценную проверку"
            parsed.transport.lowercase() !in setOf("", "tcp", "raw") ->
                "Кандидат: endpoint доступен, но transport=${parsed.transport} проверен только на доступность"
            else ->
                "Кандидат: endpoint доступен, но строгая протокольная проверка не прошла"
        }
    }

    private fun strictRealityReason(parsed: ParsedEndpoint): String {
        return "Подходит под строгий профиль REALITY/TCP/Vision: параметры валидны, endpoint доступен"
    }

    private fun isStrictRealityVisionProfile(parsed: ParsedEndpoint): Boolean {
        if (parsed.scheme != "vless") return false
        if (!parsed.security.equals("reality", ignoreCase = true)) return false
        if (parsed.transport.lowercase() !in setOf("", "tcp", "raw")) return false
        if (!parsed.flow.equals("xtls-rprx-vision", ignoreCase = true)) return false
        if (!isValidUuid(parsed.user)) return false
        if (!isValidRealityPublicKey(parsed.realityPublicKey)) return false
        if (!isValidRealityFingerprint(parsed.fingerprint)) return false
        if (!isLikelyHostname(parsed.sni)) return false
        val shortId = parsed.shortId.orEmpty()
        if (shortId.isNotBlank() && !shortId.matches(Regex("^[0-9a-fA-F]{1,16}$"))) return false
        return true
    }

    private fun isValidUuid(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return runCatching { UUID.fromString(value) }.isSuccess
    }

    private fun isValidRealityPublicKey(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.matches(Regex("^[A-Za-z0-9_-]{20,120}$"))
    }

    private fun isValidRealityFingerprint(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.lowercase() in setOf(
            "chrome", "firefox", "safari", "edge", "ios", "android", "qq", "random"
        )
    }

    private fun isLikelyHostname(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.contains(" ")) return false
        if (!value.contains('.')) return false
        return true
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
                else -> params["security"].orEmpty().ifBlank { "none" }
            }
            ParsedEndpoint(
                scheme = scheme,
                host = host,
                port = port,
                security = security,
                sni = params["sni"],
                flow = params["flow"].orEmpty(),
                transport = params["type"].orEmpty().ifBlank { "tcp" },
                path = params["path"],
                hostHeader = params["host"],
                alpn = params["alpn"]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty(),
                user = uri.userInfo,
                realityPublicKey = params["pbk"],
                shortId = params["sid"],
                fingerprint = params["fp"],
                vmessJson = null
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
            val host = json.optString("add")
            if (host.isBlank()) return null
            val port = json.optString("port").toIntOrNull() ?: return null
            val security = when {
                json.optString("security").equals("tls", ignoreCase = true) -> "tls"
                json.optString("tls").equals("tls", ignoreCase = true) -> "tls"
                json.optString("security").equals("reality", ignoreCase = true) -> "reality"
                else -> "none"
            }
            ParsedEndpoint(
                scheme = "vmess",
                host = host,
                port = port,
                security = security,
                sni = json.optString("sni").ifBlank { json.optString("host") }.ifBlank { null },
                flow = json.optString("flow"),
                transport = json.optString("net").ifBlank { "tcp" },
                path = json.optString("path").ifBlank { null },
                hostHeader = json.optString("host").ifBlank { null },
                alpn = json.optString("alpn")
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() },
                user = json.optString("id").ifBlank { null },
                realityPublicKey = json.optString("pbk").ifBlank { null },
                shortId = json.optString("sid").ifBlank { null },
                fingerprint = json.optString("fp").ifBlank { null },
                vmessJson = json
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun canonicalizeVmess(cleanLink: String): String? {
        return try {
            val encoded = cleanLink.removePrefix("vmess://")
            val jsonText = decodeBase64ToString(encoded)
            val minified = JSONObject(jsonText).toString()
            val reencoded = Base64.getEncoder().withoutPadding()
                .encodeToString(minified.toByteArray(Charsets.UTF_8))
            "vmess://$reencoded"
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

    private fun runProtocolProbe(parsed: ParsedEndpoint, timeoutMs: Int = 4500): Long? {
        return when (parsed.scheme) {
            "vless" -> testVless(parsed, timeoutMs)
            "trojan" -> testTrojan(parsed, timeoutMs)
            else -> null
        }
    }

    private fun testVless(parsed: ParsedEndpoint, timeoutMs: Int = 4500): Long? {
        val user = parsed.user ?: return null
        val uuid = runCatching { UUID.fromString(user) }.getOrNull() ?: return null
        for (target in PROBE_TARGETS) {
            val startNs = System.nanoTime()
            try {
                val socket = openServerSocket(parsed, timeoutMs)
                socket.use {
                    val out = it.getOutputStream()
                    val input = it.getInputStream()
                    out.write(buildVlessRequest(uuid, target.host, target.port))
                    out.flush()
                    val version = input.read()
                    if (version < 0) return@use
                    val addonsLength = input.read()
                    if (addonsLength < 0) return@use
                    skipFully(input, addonsLength)
                    if (!probeHttp(input, out, target.request)) return@use
                    return elapsedMs(startNs)
                }
            } catch (_: Exception) {
                // Try next probe target.
            }
        }
        return null
    }

    private fun testTrojan(parsed: ParsedEndpoint, timeoutMs: Int = 4500): Long? {
        val password = parsed.user ?: return null
        for (target in PROBE_TARGETS) {
            val startNs = System.nanoTime()
            try {
                val socket = openServerSocket(parsed, timeoutMs)
                socket.use {
                    val out = it.getOutputStream()
                    val input = it.getInputStream()
                    out.write(buildTrojanRequest(password, target.host, target.port))
                    out.flush()
                    if (!probeHttp(input, out, target.request)) return@use
                    return elapsedMs(startNs)
                }
            } catch (_: Exception) {
                // Try next probe target.
            }
        }
        return null
    }

    private fun testEndpointReachability(parsed: ParsedEndpoint, timeoutMs: Int = 3500): Long? {
        val startNs = System.nanoTime()
        return try {
            when {
                parsed.security.equals("tls", ignoreCase = true) -> {
                    Socket().use { base ->
                        base.soTimeout = timeoutMs
                        base.connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
                        wrapTls(base, parsed, timeoutMs).use { }
                    }
                }
                else -> {
                    Socket().use { socket ->
                        socket.soTimeout = timeoutMs
                        socket.connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
                    }
                }
            }
            elapsedMs(startNs)
        } catch (_: Exception) {
            null
        }
    }

    private fun probeHttp(input: InputStream, out: java.io.OutputStream, request: ByteArray): Boolean {
        out.write(request)
        out.flush()
        return looksLikeHttpResponse(input)
    }

    private fun looksLikeHttpResponse(input: InputStream): Boolean {
        val buffer = ByteArray(16)
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read <= 0) break
            total += read
            val text = String(buffer, 0, total, Charsets.ISO_8859_1)
            if (text.startsWith("HTTP/")) return true
        }
        return false
    }

    private fun buildVlessRequest(uuid: UUID, host: String, port: Int): ByteArray {
        val addressBytes = host.toByteArray(Charsets.UTF_8)
        val uuidBytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        return ByteBuffer.allocate(1 + 16 + 1 + 1 + 2 + 1 + 1 + addressBytes.size)
            .put(0x00)
            .put(uuidBytes)
            .put(0x00)
            .put(0x01)
            .putShort(port.toShort())
            .put(0x02)
            .put(addressBytes.size.toByte())
            .put(addressBytes)
            .array()
    }

    private fun buildTrojanRequest(password: String, host: String, port: Int): ByteArray {
        val passwordHash = sha224Hex(password)
        val addressBytes = host.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(56 + 2 + 1 + 1 + 1 + addressBytes.size + 2 + 2)
            .put(passwordHash.toByteArray(Charsets.US_ASCII))
            .put(CRLF)
            .put(0x01)
            .put(0x03)
            .put(addressBytes.size.toByte())
            .put(addressBytes)
            .putShort(port.toShort())
            .put(CRLF)
            .array()
    }

    private fun sha224Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-224").digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                append(((byte.toInt() ushr 4) and 0x0F).toString(16))
                append((byte.toInt() and 0x0F).toString(16))
            }
        }
    }

    private fun openServerSocket(parsed: ParsedEndpoint, timeoutMs: Int): Socket {
        val base = Socket().apply {
            soTimeout = timeoutMs
            connect(InetSocketAddress(parsed.host, parsed.port), timeoutMs)
        }
        return if (parsed.security.equals("tls", ignoreCase = true)) {
            wrapTls(base, parsed, timeoutMs)
        } else {
            base
        }
    }

    private fun wrapTls(base: Socket, parsed: ParsedEndpoint, timeoutMs: Int): SSLSocket {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        val socket = sslContext.socketFactory.createSocket(base, parsed.host, parsed.port, true) as SSLSocket
        socket.soTimeout = timeoutMs
        val params = socket.sslParameters
        val serverName = parsed.sni ?: parsed.hostHeader ?: parsed.host
        params.serverNames = listOf(SNIHostName(serverName))
        if (parsed.alpn.isNotEmpty()) {
            runCatching { params.applicationProtocols = parsed.alpn.toTypedArray() }
        }
        socket.sslParameters = params
        socket.startHandshake()
        return socket
    }

    private fun skipFully(input: InputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(256)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (read <= 0) break
            remaining -= read
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
        val sni: String?,
        val flow: String,
        val transport: String,
        val path: String?,
        val hostHeader: String?,
        val alpn: List<String>,
        val user: String?,
        val realityPublicKey: String?,
        val shortId: String?,
        val fingerprint: String?,
        val vmessJson: JSONObject?
    )

    private val CRLF = byteArrayOf(0x0D, 0x0A)
}
