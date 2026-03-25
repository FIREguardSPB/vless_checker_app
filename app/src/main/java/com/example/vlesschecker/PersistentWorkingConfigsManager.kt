package com.example.vlesschecker

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.min

object PersistentWorkingConfigsManager {
    private const val PREFS_KEY = "persistent_working_configs"
    private const val KEY_CONFIGS_LIST = "configs_list"
    private const val DEFAULT_MAX_CONFIGS = 10
    private const val CONFIG_STALE_HOURS = 24L // Конфиги устаревают через 24 часа
    
    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
    }
    
    /**
     * Get maximum number of working configs to keep.
     */
    fun getMaxConfigs(context: Context): Int {
        return AppPrefs.getMaxWorkingConfigs(context).takeIf { it > 0 } ?: DEFAULT_MAX_CONFIGS
    }
    
    /**
     * Set maximum number of working configs to keep.
     */
    fun setMaxConfigs(context: Context, max: Int) {
        AppPrefs.setMaxWorkingConfigs(context, max.coerceAtLeast(1))
    }
    
    /**
     * Get all persisted configs, sorted by latency (fastest first).
     */
    fun getConfigs(context: Context): List<PersistedConfig> {
        val json = prefs(context).getString(KEY_CONFIGS_LIST, "[]").orEmpty()
        val configs = PersistedConfig.listFromJson(json)
        return cleanupOldConfigs(context, configs).sortedBy { it.latencyMs }
    }
    
    /**
     * Save configs list.
     */
    private fun saveConfigs(context: Context, configs: List<PersistedConfig>) {
        val limited = applyMaxLimit(configs, getMaxConfigs(context))
        val json = PersistedConfig.listToJson(limited)
        prefs(context).edit().putString(KEY_CONFIGS_LIST, json).apply()
    }
    
    /**
     * Update stored configs with new check results.
     * 
     * Strategy:
     * 1. Remove configs that are no longer working (not in newResults).
     * 2. Update latency for existing configs if new result is faster.
     * 3. Add new working configs up to max limit.
     * 4. Keep fastest configs overall.
     */
    fun updateWithNewResults(
        context: Context,
        newResults: List<LinkCheckResult>,
        source: ListSource
    ): List<PersistedConfig> {
        val current = getConfigs(context)
        val maxConfigs = getMaxConfigs(context)
        
        // Group current configs by link for quick lookup
        val currentByLink = current.associateBy { it.link }
        
        // Create map of new working results by link
        val newWorkingByLink = newResults
            .filter { it.isWorking && it.latencyMs != null && it.confidence != null }
            .associateBy { it.link }
        
        val updated = mutableListOf<PersistedConfig>()
        
        // Update existing configs
        current.forEach { config ->
            val newResult = newWorkingByLink[config.link]
            if (newResult != null) {
                // Config still working, update if faster
                val newLatency = newResult.latencyMs!!
                val updatedConfig = config.copy(
                    latencyMs = min(config.latencyMs, newLatency),
                    lastCheckedTime = System.currentTimeMillis(),
                    country = newResult.country ?: config.country,
                    flag = newResult.flag ?: config.flag,
                    metadata = newResult.metadata ?: config.metadata,
                    source = source
                )
                updated.add(updatedConfig)
            } else {
                // Config not in new results - keep it if not too old
                val ageHours = (System.currentTimeMillis() - config.lastCheckedTime) / (1000 * 60 * 60)
                if (ageHours < CONFIG_STALE_HOURS) {
                    updated.add(config) // Keep stale config for now
                }
                // Otherwise drop it
            }
        }
        
        // Add new configs that aren't already in the list
        newWorkingByLink.forEach { (link, result) ->
            if (!currentByLink.containsKey(link)) {
                updated.add(PersistedConfig.fromLinkCheckResult(result, source))
            }
        }
        
        // Apply max limit, keeping fastest configs
        val finalList = applyMaxLimit(updated, maxConfigs)
        saveConfigs(context, finalList)
        return finalList
    }
    
    /**
     * Clear all persisted configs.
     */
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CONFIGS_LIST).apply()
    }
    
    /**
     * Remove configs older than CONFIG_STALE_HOURS.
     */
    private fun cleanupOldConfigs(context: Context, configs: List<PersistedConfig>): List<PersistedConfig> {
        val staleCutoff = System.currentTimeMillis() - (CONFIG_STALE_HOURS * 1000 * 60 * 60)
        val fresh = configs.filter { it.lastCheckedTime >= staleCutoff }
        if (fresh.size != configs.size) {
            saveConfigs(context, fresh)
        }
        return fresh
    }
    
    /**
     * Limit list to maxConfigs, keeping fastest ones.
     */
    private fun applyMaxLimit(configs: List<PersistedConfig>, maxConfigs: Int): List<PersistedConfig> {
        return configs.sortedBy { it.latencyMs }.take(maxConfigs)
    }
    
    /**
     * Get only the fastest configs up to limit.
     */
    fun getFastestConfigs(context: Context, limit: Int = Int.MAX_VALUE): List<PersistedConfig> {
        val configs = getConfigs(context)
        return configs.take(min(limit, configs.size))
    }
    
    /**
     * Check if a link is already in persisted configs.
     */
    fun containsLink(context: Context, link: String): Boolean {
        return getConfigs(context).any { it.link == link }
    }
    
    /**
     * Get number of persisted configs.
     */
    fun count(context: Context): Int {
        return getConfigs(context).size
    }
}