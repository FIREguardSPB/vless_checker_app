package com.example.vlesschecker

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

/**
 * Represents a working proxy configuration that should be persisted across app sessions.
 */
data class PersistedConfig(
    val link: String,
    val latencyMs: Long,
    val lastCheckedTime: Long = System.currentTimeMillis(),
    val country: String? = null,
    val flag: String? = null,
    val metadata: String? = null,
    val source: ListSource = ListSource.Manual
) {
    companion object {
        private val gson = Gson()
        private val listType = object : TypeToken<List<PersistedConfig>>() {}.type
        
        fun fromLinkCheckResult(result: LinkCheckResult, source: ListSource): PersistedConfig {
            return PersistedConfig(
                link = result.link,
                latencyMs = result.latencyMs ?: Long.MAX_VALUE,
                lastCheckedTime = System.currentTimeMillis(),
                country = result.country,
                flag = result.flag,
                metadata = result.metadata,
                source = source
            )
        }
        
        fun listToJson(configs: List<PersistedConfig>): String {
            return gson.toJson(configs)
        }
        
        fun listFromJson(json: String): List<PersistedConfig> {
            return try {
                gson.fromJson(json, listType) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}