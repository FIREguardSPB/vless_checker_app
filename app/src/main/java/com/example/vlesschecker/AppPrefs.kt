package com.example.vlesschecker

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "vless_checker_prefs"
    private const val KEY_MANUAL_SERVER_LIST = "manual_server_list"
    private const val KEY_SELECTED_SOURCE = "selected_source"
    private const val KEY_REMOTE_AVAILABLE_CACHE = "remote_available_cache"
    private const val KEY_REMOTE_WHITELIST_CACHE = "remote_whitelist_cache"
    private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val KEY_AUTO_CHECK_INTERVAL_MIN = "auto_check_interval_min"
    private const val KEY_DELETE_DEAD_ON_FULL_SCAN = "delete_dead_on_full_scan"
    private const val KEY_HIDE_CANDIDATES = "hide_candidates"
    private const val KEY_LAST_AUTO_NOTIFIED_LINK = "last_auto_notified_link"
    private const val KEY_LAST_FASTEST_LINK = "last_fastest_link"

    fun getServerList(context: Context): String {
        return prefs(context).getString(KEY_MANUAL_SERVER_LIST, "").orEmpty()
    }

    fun setServerList(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MANUAL_SERVER_LIST, value).apply()
    }

    fun getSelectedSource(context: Context): ListSource {
        return ListSource.fromPrefValue(prefs(context).getString(KEY_SELECTED_SOURCE, ListSource.MANUAL.prefValue))
    }

    fun setSelectedSource(context: Context, value: ListSource) {
        prefs(context).edit().putString(KEY_SELECTED_SOURCE, value.prefValue).apply()
    }

    fun getRemoteCache(context: Context, source: ListSource): String {
        val key = when (source) {
            ListSource.XRAY_AVAILABLE_ST_TOP100 -> KEY_REMOTE_AVAILABLE_CACHE
            ListSource.XRAY_WHITE_LIST_ST_TOP100 -> KEY_REMOTE_WHITELIST_CACHE
            ListSource.MANUAL -> return ""
        }
        return prefs(context).getString(key, "").orEmpty()
    }

    fun setRemoteCache(context: Context, source: ListSource, value: String) {
        val key = when (source) {
            ListSource.XRAY_AVAILABLE_ST_TOP100 -> KEY_REMOTE_AVAILABLE_CACHE
            ListSource.XRAY_WHITE_LIST_ST_TOP100 -> KEY_REMOTE_WHITELIST_CACHE
            ListSource.MANUAL -> return
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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
