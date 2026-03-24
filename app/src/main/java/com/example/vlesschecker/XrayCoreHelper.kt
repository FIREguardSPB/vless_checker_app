package com.example.vlesschecker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import libv2ray.Libv2ray
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
    private const val TEST_TIMEOUT_SECONDS = 30L

    private var isInitialized = false
    private var isLibraryAvailable = false

    /**
     * Initialize AndroidLibXrayLite library if available.
     */
    private fun initLibraryIfAvailable(context: Context) {
        if (isLibraryAvailable) return
        
        try {
            // Check if Libv2ray class is loadable
            Class.forName("libv2ray.Libv2ray")
            
            // Initialize environment with data directory (where geoip.dat, geosite.dat are stored)
            val dataDir = getDataDir(context)
            Libv2ray.initCoreEnv(dataDir.absolutePath, "")
            
            // Test library version
            val version = Libv2ray.checkVersionX()
            Log.d(TAG, "AndroidLibXrayLite loaded: $version")
            isLibraryAvailable = true
        } catch (e: ClassNotFoundException) {
            Log.w(TAG, "AndroidLibXrayLite not available in classpath: ${e.message}")
            isLibraryAvailable = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidLibXrayLite: ${e.message}", e)
            isLibraryAvailable = false
        }
    }

    /**
     * Get the best directory for storing executable binary.
     * Prefers externalCacheDir (if available and writable), falls back to cacheDir.
     */
    private fun getBinaryDir(context: Context): File {
        // Try externalCacheDir first (less restrictive SELinux)
        context.externalCacheDir?.let { externalDir ->
            val dir = File(externalDir, XRAY_DIR_NAME)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            // Test writability
            val testFile = File(dir, "write_test")
            try {
                testFile.writeText("test")
                testFile.delete()
                Log.d(TAG, "Using externalCacheDir: ${dir.absolutePath}")
                return dir
            } catch (e: Exception) {
                Log.w(TAG, "externalCacheDir not writable: ${e.message}")
            }
        }
        // Fallback to internal cacheDir
        val dir = File(context.cacheDir, XRAY_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        Log.d(TAG, "Using cacheDir: ${dir.absolutePath}")
        return dir
    }

    /**
     * Get directory for data files (geoip.dat, geosite.dat).
     */
    private fun getDataDir(context: Context): File {
        val dir = File(context.filesDir, XRAY_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Ensure xray binary and data files are copied from assets to internal storage.
     * Must be called before any test execution.
     * Uses the best available directory for binary.
     */
    suspend fun ensureInitialized(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext

        val xrayDir = getBinaryDir(context)
        val dataDir = getDataDir(context)

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

        // Test binary works
        try {
            val process = ProcessBuilder(binaryFile.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(10, TimeUnit.SECONDS)
            if (completed) {
                val output = process.inputStream.bufferedReader().readText()
                Log.d(TAG, "Xray version check success: ${output.take(200)}")
            } else {
                process.destroy()
                Log.w(TAG, "Xray version check timeout")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Xray version check failed: ${e.message}", e)
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

        val binaryDir = getBinaryDir(context)
        val dataDir = getDataDir(context)
        val binaryFile = File(binaryDir, XRAY_BINARY_NAME)
        
        // Check binary existence and permissions
        if (!binaryFile.exists()) {
            Log.e(TAG, "Binary file does not exist: ${binaryFile.absolutePath}")
            return@withContext TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Binary not found: ${binaryFile.absolutePath}"
            )
        }
        
        // Check data files
        val geoipFile = File(dataDir, GEOIP_NAME)
        val geositeFile = File(dataDir, GEOSITE_NAME)
        Log.d(TAG, "Data files check: geoip=${geoipFile.absolutePath}, exists=${geoipFile.exists()}, size=${if (geoipFile.exists()) geoipFile.length() else 0}")
        Log.d(TAG, "Data files check: geosite=${geositeFile.absolutePath}, exists=${geositeFile.exists()}, size=${if (geositeFile.exists()) geositeFile.length() else 0}")
        
        if (!geoipFile.exists() || !geositeFile.exists()) {
            Log.e(TAG, "Missing data files, re-initializing...")
            isInitialized = false
            ensureInitialized(context)
            // Check again
            if (!geoipFile.exists() || !geositeFile.exists()) {
                return@withContext TestResult(
                    success = false,
                    latencyMs = null,
                    errorMessage = "Missing xray data files (geoip.dat, geosite.dat)"
                )
            }
        }
        
        Log.d(TAG, "Binary file info: path=${binaryFile.absolutePath}, size=${binaryFile.length()}, readable=${binaryFile.canRead()}, writable=${binaryFile.canWrite()}, executable=${binaryFile.canExecute()}")
        
        // Try to make executable if not already
        if (!binaryFile.canExecute()) {
            try {
                binaryFile.setExecutable(true, false)
                Log.d(TAG, "Attempted to set executable permission")
            } catch (e: SecurityException) {
                Log.e(TAG, "Cannot set executable permission: ${e.message}")
            }
            // Check again
            if (!binaryFile.canExecute()) {
                Log.e(TAG, "Binary still not executable after setExecutable")
                // Continue anyway - some systems report false but binary can still be executed
            }
        }

        // Create temporary config file
        val configFile = File.createTempFile("xray_test_", ".json", dataDir)
        try {
            val configJson = generateXrayConfig(link)
            configFile.writeText(configJson, Charsets.UTF_8)
            Log.d(TAG, "Created config file: ${configFile.absolutePath} (${configJson.length} bytes)")
            Log.d(TAG, "Config preview (first 500 chars): ${configJson.take(500)}")

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
                    .redirectErrorStream(false)
                    .start().also {
                        Log.d(TAG, "Process started via ProcessBuilder")
                    }
            } catch (e: Exception) {
                Log.e(TAG, "ProcessBuilder failed: ${e.message}", e)
                // Try fallback with Runtime.exec
                try {
                    Log.d(TAG, "Trying Runtime.exec fallback...")
                    val cmd = command.toTypedArray()
                    val process = Runtime.getRuntime().exec(cmd, null, dataDir)
                    Log.d(TAG, "Process started via Runtime.exec")
                    process
                } catch (e2: Exception) {
                    Log.e(TAG, "Runtime.exec also failed: ${e2.message}", e2)
                    // Third attempt: try via sh -c (might bypass SELinux restrictions)
                    try {
                        Log.d(TAG, "Trying sh -c fallback...")
                        val shellCmd = arrayOf(
                            "sh",
                            "-c",
                            "cd \"${dataDir.absolutePath}\" && \"${binaryFile.absolutePath}\" test -config \"${configFile.absolutePath}\""
                        )
                        val process = Runtime.getRuntime().exec(shellCmd)
                        Log.d(TAG, "Process started via sh -c")
                        process
                    } catch (e3: Exception) {
                        Log.e(TAG, "sh -c also failed: ${e3.message}", e3)
                        return@withContext TestResult(
                            success = false,
                            latencyMs = null,
                            errorMessage = "Cannot start process: ${e.message} (Runtime.exec: ${e2.message}, sh -c: ${e3.message})"
                        )
                    }
                }
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
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                Log.d(TAG, "Process exit code: $exitCode, stdout length: ${stdout.length}, stderr length: ${stderr.length}")
                if (stderr.isNotBlank()) {
                    Log.d(TAG, "Process stderr (first 500 chars): ${stderr.take(500)}")
                }
                Log.d(TAG, "Raw stdout (first 1000 chars): ${stdout.take(1000)}")
                
                if (exitCode == 0) {
                    stdout
                } else {
                    Log.w(TAG, "xray failed (exit $exitCode): ${stdout.take(500)}")
                    stdout
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

    /**
     * Test a single proxy link using AndroidLibXrayLite library.
     * This bypasses SELinux restrictions as it uses native library instead of binary.
     */
    suspend fun testLinkWithLibrary(context: Context, link: String): TestResult = withContext(Dispatchers.IO) {
        // Ensure data files are copied
        ensureInitialized(context)
        
        // Initialize library if not already
        initLibraryIfAvailable(context)
        if (!isLibraryAvailable) {
            return@withContext TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "AndroidLibXrayLite library not available"
            )
        }
        
        try {
            val configJson = generateXrayConfig(link)
            Log.d(TAG, "Testing config with AndroidLibXrayLite, config length: ${configJson.length}")
            Log.d(TAG, "Config preview (first 500 chars): ${configJson.take(500)}")
            
            // Use a simple test URL (Google's 204 page)
            val testUrl = "https://www.google.com/generate_204"
            
            // Measure outbound delay - this will start xray-core internally, test the config,
            // and return latency in milliseconds or throw exception if config is invalid
            val latency = Libv2ray.measureOutboundDelay(configJson, testUrl)
            
            Log.d(TAG, "AndroidLibXrayLite returned latency: ${latency}ms")
            
            if (latency >= 0) {
                // Success - config is valid and connection works
                TestResult(
                    success = true,
                    latencyMs = latency,
                    errorMessage = null
                )
            } else {
                // Negative latency indicates error
                TestResult(
                    success = false,
                    latencyMs = null,
                    errorMessage = "xray-core library returned error (latency -1)"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in testLinkWithLibrary: ${e.message}", e)
            TestResult(
                success = false,
                latencyMs = null,
                errorMessage = "Library error: ${e.message}"
            )
        }
    }

    /**
     * Test a link using the best available method (library preferred).
     * If library is available, use it. Otherwise fall back to binary (which may not work on Android 16+).
     */
    suspend fun testLinkBest(context: Context, link: String): TestResult = withContext(Dispatchers.IO) {
        // Try library first
        initLibraryIfAvailable(context)
        if (isLibraryAvailable) {
            Log.d(TAG, "Using AndroidLibXrayLite for testing")
            return@withContext testLinkWithLibrary(context, link)
        }
        
        // Fall back to binary (legacy method)
        Log.d(TAG, "AndroidLibXrayLite not available, falling back to binary")
        return@withContext testLink(context, link)
    }

    data class TestResult(
        val success: Boolean,
        val latencyMs: Long?,
        errorMessage: String?
    )
}