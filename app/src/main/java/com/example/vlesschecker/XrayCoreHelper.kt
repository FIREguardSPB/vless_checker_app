package com.example.vlesschecker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * Helper for executing xray-core binary to test VLESS/VMess/Trojan configurations.
 * Downloads binary from assets to app's internal storage and runs `xray test -config`.
 */
object XrayCoreHelper {
    private const val TAG = "XrayCoreHelper"
    private const val XRAY_ASSETS_DIR = "xray"
    private const val XRAY_BINARY_NAME = "xray"
    private const val GEOIP_NAME = "geoip.dat"
    private const val GEOSITE_NAME = "geosite.dat"
    private const val XRAY_DIR_NAME = "xray"
    private const val TEST_TIMEOUT_SECONDS = 15L

    private var isInitialized = false

    /**
     * Ensure xray binary and data files are copied from assets to internal storage.
     * Must be called before any test execution.
     * Uses codeCacheDir for binary (executable allowed there).
     */
    suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        // Use codeCacheDir for binary (executable permission allowed)
        val xrayDir = File(context.codeCacheDir, XRAY_DIR_NAME)
        if (!xrayDir.exists()) {
            xrayDir.mkdirs()
        }

        // Data files can stay in filesDir
        val dataDir = File(context.filesDir, XRAY_DIR_NAME)
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }

        val binaryFile = File(xrayDir, XRAY_BINARY_NAME)
        val geoipFile = File(dataDir, GEOIP_NAME)
        val geositeFile = File(dataDir, GEOSITE_NAME)

        // Copy binary if not exists
        if (!binaryFile.exists()) {
            copyAssetToFile(context, "$XRAY_ASSETS_DIR/$XRAY_BINARY_NAME", binaryFile)
            // Try to make executable
            try {
                binaryFile.setExecutable(true, false)
                Log.d(TAG, "xray binary copied and made executable: ${binaryFile.absolutePath}")
            } catch (e: SecurityException) {
                Log.e(TAG, "Failed to set executable permissions: ${e.message}")
                // Try alternative: chmod via Runtime
                try {
                    Runtime.getRuntime().exec(arrayOf("chmod", "755", binaryFile.absolutePath)).waitFor()
                    Log.d(TAG, "chmod 755 applied via Runtime")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to chmod via Runtime: ${e2.message}")
                }
            }
        }

        // Check binary permissions
        if (!binaryFile.canExecute()) {
            Log.w(TAG, "Binary not executable: ${binaryFile.absolutePath}, exists: ${binaryFile.exists()}, readable: ${binaryFile.canRead()}")
        }

        // Copy geoip.dat if not exists
        if (!geoipFile.exists()) {
            copyAssetToFile(context, "$XRAY_ASSETS_DIR/$GEOIP_NAME", geoipFile)
            Log.d(TAG, "geoip.dat copied to ${geoipFile.absolutePath}")
        }

        // Copy geosite.dat if not exists
        if (!geositeFile.exists()) {
            copyAssetToFile(context, "$XRAY_ASSETS_DIR/$GEOSITE_NAME", geositeFile)
            Log.d(TAG, "geosite.dat copied to ${geositeFile.absolutePath}")
        }

        isInitialized = true
        Log.d(TAG, "xray-core initialized: binary=${binaryFile.absolutePath}, data=${dataDir.absolutePath}")
    }

    private fun copyAssetToFile(context: Context, assetPath: String, destFile: File) {
        try {
            context.assets.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy asset $assetPath to ${destFile.absolutePath}", e)
            throw RuntimeException("Failed to copy xray assets", e)
        }
    }

    /**
     * Test a single proxy link using xray-core `test` command.
     * @param link VLESS/VMess/Trojan link
     * @return Pair of (success: Boolean, latencyMs: Long?, errorMessage: String?)
     */
    suspend fun testLink(context: Context, link: String): TestResult = withContext(Dispatchers.IO) {
        ensureInitialized(context)

        val binaryDir = File(context.codeCacheDir, XRAY_DIR_NAME)
        val dataDir = File(context.filesDir, XRAY_DIR_NAME)
        val binaryFile = File(binaryDir, XRAY_BINARY_NAME)
        
        // Check binary existence and permissions
        if (!binaryFile.exists()) {
            return@withContext TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Binary not found: ${binaryFile.absolutePath}"
            )
        }
        
        if (!binaryFile.canExecute()) {
            return@withContext TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Binary not executable: ${binaryFile.absolutePath}"
            )
        }

        // Create temporary config file
        val configFile = File.createTempFile("xray_test_", ".json", dataDir)
        try {
            val configJson = generateXrayConfig(link)
            configFile.writeText(configJson, Charsets.UTF_8)
            Log.d(TAG, "Created config file: ${configFile.absolutePath} (${configJson.length} bytes)")

            // Run xray test command
            val command = listOf(
                binaryFile.absolutePath,
                "test",
                "-config",
                configFile.absolutePath
            )
            Log.d(TAG, "Executing: ${command.joinToString(" ")}")
            
            val process = try {
                ProcessBuilder(command)
                    .directory(dataDir)
                    .redirectErrorStream(true)
                    .start()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start process: ${e.message}", e)
                return@withContext TestResult(
                    success = false,
                    latencyMs = null,
                    errorMessage = "Cannot start process: ${e.message}"
                )
            }

            val output = try {
                val completed = process.waitFor(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                if (!completed) {
                    process.destroy()
                    return@withContext TestResult(
                        success = false,
                        latencyMs = null,
                        errorMessage = "Timeout after ${TEST_TIMEOUT_SECONDS}s"
                    )
                }
                
                val exitCode = process.exitValue()
                val outputText = process.inputStream.bufferedReader().readText()
                Log.d(TAG, "Process exit code: $exitCode, output length: ${outputText.length}")
                
                if (exitCode == 0) {
                    outputText
                } else {
                    Log.w(TAG, "xray failed (exit $exitCode): ${outputText.take(500)}")
                    outputText
                }
            } catch (e: Exception) {
                process.destroy()
                Log.e(TAG, "Process execution error: ${e.message}", e)
                return@withContext TestResult(
                    success = false,
                    latencyMs = null,
                    errorMessage = "Process error: ${e.message}"
                )
            }

            // Parse JSON output
            return@withContext parseXrayOutput(output)
        } catch (e: Exception) {
            Log.e(TAG, "Error testing link with xray", e)
            return@withContext TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Internal error: ${e.message}"
            )
        } finally {
            try {
                configFile.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete config file: ${e.message}")
            }
        }
    }

    /**
     * Generate minimal xray JSON config from a single link.
     * Supports VLESS, VMess, Trojan.
     */
    private fun generateXrayConfig(link: String): String {
        val parsed = VlessChecker.parseLink(link) ?: throw IllegalArgumentException("Invalid link: $link")
        return generateXrayConfigFromParsed(parsed)
    }

    private fun generateXrayConfigFromParsed(parsed: VlessChecker.ParsedEndpoint): String {
        val outbound = JSONObject().apply {
            put("protocol", parsed.scheme)
            put("settings", JSONObject().apply {
                when (parsed.scheme) {
                    "vless", "trojan" -> {
                        val vnext = JSONArray()
                        val server = JSONObject().apply {
                            put("address", parsed.host)
                            put("port", parsed.port)
                            val users = JSONArray()
                            val user = JSONObject().apply {
                                put("id", parsed.user ?: "")
                                put("encryption", "none")
                                put("flow", parsed.flow.takeIf { it.isNotEmpty() } ?: "")
                            }
                            users.put(user)
                            put("users", users)
                        }
                        vnext.put(server)
                        put("vnext", vnext)
                    }
                    "vmess" -> {
                        val vnext = JSONArray()
                        val server = JSONObject().apply {
                            put("address", parsed.host)
                            put("port", parsed.port)
                            val users = JSONArray()
                            val user = JSONObject().apply {
                                put("id", parsed.user ?: "")
                                put("alterId", 0)
                                put("security", "auto")
                            }
                            users.put(user)
                            put("users", users)
                        }
                        vnext.put(server)
                        put("vnext", vnext)
                    }
                    else -> {
                        // fallback
                    }
                }
            })
            put("streamSettings", JSONObject().apply {
                put("network", parsed.transport)
                if (parsed.security.equals("tls", ignoreCase = true) ||
                    parsed.security.equals("reality", ignoreCase = true)) {
                    val tlsSettings = JSONObject().apply {
                        put("serverName", parsed.sni ?: parsed.host)
                        put("allowInsecure", parsed.allowInsecure)
                        if (parsed.fingerprint != null) {
                            put("fingerprint", parsed.fingerprint)
                        }
                        if (parsed.security.equals("reality", ignoreCase = true)) {
                            val realitySettings = JSONObject().apply {
                                put("publicKey", parsed.realityPublicKey ?: "")
                                if (parsed.shortId != null) {
                                    put("shortId", parsed.shortId)
                                }
                                put("serverName", parsed.sni ?: parsed.host)
                                put("fingerprint", parsed.fingerprint ?: "chrome")
                            }
                            put("realitySettings", realitySettings)
                        }
                    }
                    put("security", parsed.security)
                    put("tlsSettings", tlsSettings)
                }
                if (parsed.transport == "ws") {
                    val wsSettings = JSONObject().apply {
                        put("path", parsed.path ?: "/")
                        if (parsed.hostHeader != null) {
                            val headers = JSONObject()
                            headers.put("Host", parsed.hostHeader)
                            put("headers", headers)
                        }
                    }
                    put("wsSettings", wsSettings)
                }
                if (parsed.alpn.isNotEmpty()) {
                    put("alpn", JSONArray(parsed.alpn))
                }
            })
            put("tag", "proxy")
        }

        val config = JSONObject().apply {
            put("log", JSONObject().apply {
                put("loglevel", "warning")
            })
            // Dummy inbound required for xray test
            put("inbounds", JSONArray().apply {
                val inbound = JSONObject().apply {
                    put("port", 1080)
                    put("listen", "127.0.0.1")
                    put("protocol", "socks")
                    put("settings", JSONObject().apply {
                        put("auth", "noauth")
                        put("udp", true)
                    })
                }
                put(inbound)
            })
            put("outbounds", JSONArray().apply {
                put(outbound)
            })
        }
        return config.toString(2)
    }

    /**
     * Parse xray test command output.
     * Expected format: JSON with "success", "latency", "error" fields.
     */
    private fun parseXrayOutput(output: String): TestResult {
        return try {
            val json = JSONObject(output)
            val success = json.optBoolean("success", false)
            val latency = json.optLong("latency", -1).takeIf { it > 0 }
            val error = json.optString("error", "").takeIf { it.isNotBlank() }
            TestResult(success, latency, error)
        } catch (e: Exception) {
            // If output is not JSON, assume failure
            TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Invalid output: ${output.take(200)}"
            )
        }
    }

    data class TestResult(
        val success: Boolean,
        val latencyMs: Long?,
        val errorMessage: String?
    )
}