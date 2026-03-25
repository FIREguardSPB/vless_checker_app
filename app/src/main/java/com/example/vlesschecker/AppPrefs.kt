package com.example.vlesschecker

import android.content.Context

object AppPrefs {
    internal const val PREFS_NAME = "vless_checker_prefs"
    private const val KEY_MANUAL_SERVER_LIST = "manual_server_list"
    private const val KEY_SELECTED_SOURCE = "selected_source"
    private const val KEY_REMOTE_AVAILABLE_CACHE = "remote_available_cache"
    private const val KEY_REMOTE_WHITELIST_CACHE = "remote_whitelist_cache"
    private const val KEY_REMOTE_USER_DEFINED_CACHE = "remote_user_defined_cache"
    private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val KEY_AUTO_CHECK_INTERVAL_MIN = "auto_check_interval_min"
    private const val KEY_DELETE_DEAD_ON_FULL_SCAN = "delete_dead_on_full_scan"
    private const val KEY_HIDE_CANDIDATES = "hide_candidates"
    private const val KEY_LAST_AUTO_NOTIFIED_LINK = "last_auto_notified_link"
    private const val KEY_LAST_FASTEST_LINK = "last_fastest_link"
    private const val KEY_USER_DEFINED_URL = "user_defined_url"
    private const val KEY_USER_DEFINED_NAME = "user_defined_name"

    fun getServerList(context: Context): String {
        return prefs(context).getString(KEY_MANUAL_SERVER_LIST, "").orEmpty()
    }

    fun setServerList(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MANUAL_SERVER_LIST, value).apply()
    }

    fun getSelectedSource(context: Context): ListSource {
        return ListSource.fromPrefValue(context, prefs(context).getString(KEY_SELECTED_SOURCE, ListSource.Manual.prefValue))
    }

    fun setSelectedSource(context: Context, value: ListSource) {
        prefs(context).edit().putString(KEY_SELECTED_SOURCE, value.prefValue).apply()
    }

    fun getRemoteCache(context: Context, source: ListSource): String {
        val key = when (source) {
            is ListSource.XrayAvailable -> KEY_REMOTE_AVAILABLE_CACHE
            is ListSource.XrayWhitelist -> KEY_REMOTE_WHITELIST_CACHE
            is ListSource.UserDefined -> KEY_REMOTE_USER_DEFINED_CACHE
            is ListSource.Manual -> return ""
        }
        return prefs(context).getString(key, "").orEmpty()
    }

    fun setRemoteCache(context: Context, source: ListSource, value: String) {
        val key = when (source) {
            is ListSource.XrayAvailable -> KEY_REMOTE_AVAILABLE_CACHE
            is ListSource.XrayWhitelist -> KEY_REMOTE_WHITELIST_CACHE
            is ListSource.UserDefined -> KEY_REMOTE_USER_DEFINED_CACHE
            is ListSource.Manual -> return
        }
        prefs(context).edit().putString(key, value).apply()
    }

    fun isAutoCheckEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_AUTO_CHECK_ENABLED, false)
    }

    fun setAutoCheckEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK_ENABLED, value).apply()
    }

    fun getAutoCheckIntervalMinutes(context: Context): Int {
        return prefs(context).getInt(KEY_AUTO_CHECK_INTERVAL_MIN, 15)
    }

    fun setAutoCheckIntervalMinutes(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_AUTO_CHECK_INTERVAL_MIN, value).apply()
    }

    fun isDeleteDeadOnFullScan(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DELETE_DEAD_ON_FULL_SCAN, false)
    }

    fun setDeleteDeadOnFullScan(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_DELETE_DEAD_ON_FULL_SCAN, value).apply()
    }

    fun isHideCandidates(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_CANDIDATES, false)
    }

    fun setHideCandidates(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIDE_CANDIDATES, value).apply()
    }

    fun getLastAutoNotifiedLink(context: Context): String {
        return prefs(context).getString(KEY_LAST_AUTO_NOTIFIED_LINK, "").orEmpty()
    }

    fun setLastAutoNotifiedLink(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_AUTO_NOTIFIED_LINK, value).apply()
    }

    fun getLastFastestLink(context: Context): String {
        return prefs(context).getString(KEY_LAST_FASTEST_LINK, "").orEmpty()
    }

    fun setLastFastestLink(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_FASTEST_LINK, value).apply()
    }

    fun getUserDefinedUrl(context: Context): String {
        // Try to get from new UserSourceManager first
        val enabledSource = UserSourceManager.getFirstEnabled(context)
        if (enabledSource != null) {
            // Migrate old data if exists
            migrateOldUserSource(context)
            return enabledSource.url
        }
        
        // Fallback to old storage
        val oldUrl = prefs(context).getString(KEY_USER_DEFINED_URL, "").orEmpty()
        if (oldUrl.isNotBlank()) {
            // Migrate old URL to new system
            val oldName = prefs(context).getString(KEY_USER_DEFINED_NAME, "").orEmpty()
            val source = UserSource(
                name = if (oldName.isNotBlank()) oldName else "Импортированный источник",
                url = oldUrl,
                isEnabled = true
            )
            UserSourceManager.add(context, source)
            // Clear old keys
            prefs(context).edit()
                .remove(KEY_USER_DEFINED_URL)
                .remove(KEY_USER_DEFINED_NAME)
                .apply()
            return oldUrl
        }
        
        return ""
    }

    fun setUserDefinedUrl(context: Context, value: String) {
        var enabledSource = UserSourceManager.getFirstEnabled(context)
        if (enabledSource != null) {
            enabledSource = enabledSource.copy(url = value)
            UserSourceManager.update(context, enabledSource)
        } else {
            // Create new source
            val source = UserSource(
                name = "Пользовательский URL",
                url = value,
                isEnabled = true
            )
            UserSourceManager.add(context, source)
        }
        // Keep old storage for compatibility
        prefs(context).edit().putString(KEY_USER_DEFINED_URL, value).apply()
    }

    fun getUserDefinedName(context: Context): String {
        val enabledSource = UserSourceManager.getFirstEnabled(context)
        if (enabledSource != null) {
            migrateOldUserSource(context)
            return enabledSource.name
        }
        return prefs(context).getString(KEY_USER_DEFINED_NAME, "").orEmpty()
    }

    fun setUserDefinedName(context: Context, value: String) {
        val enabledSource = UserSourceManager.getFirstEnabled(context)
        if (enabledSource != null) {
            val updated = enabledSource.copy(name = value)
            UserSourceManager.update(context, updated)
        }
        prefs(context).edit().putString(KEY_USER_DEFINED_NAME, value).apply()
    }

    private fun migrateOldUserSource(context: Context) {
        val oldUrl = prefs(context).getString(KEY_USER_DEFINED_URL, "").orEmpty()
        if (oldUrl.isNotBlank()) {
            val oldName = prefs(context).getString(KEY_USER_DEFINED_NAME, "").orEmpty()
            val source = UserSource(
                name = if (oldName.isNotBlank()) oldName else "Импортированный источник",
                url = oldUrl,
                isEnabled = true
            )
            UserSourceManager.add(context, source)
            prefs(context).edit()
                .remove(KEY_USER_DEFINED_URL)
                .remove(KEY_USER_DEFINED_NAME)
                .apply()
        }
    }

    fun getMaxWorkingConfigs(context: Context): Int {
        return prefs(context).getInt("max_working_configs", 10)
    }

    fun setMaxWorkingConfigs(context: Context, value: Int) {
        prefs(context).edit().putInt("max_working_configs", value).apply()
    }

    fun getMaxLatencyMs(context: Context): Long {
        return prefs(context).getLong("max_latency_ms", 1000L)  // default 1000 ms
    }

    fun setMaxLatencyMs(context: Context, value: Long) {
        prefs(context).edit().putLong("max_latency_ms", value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
