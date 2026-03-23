package com.example.vlesschecker

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "vless_checker_prefs"
    private const val KEY_SERVER_LIST = "server_list"
    private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val KEY_AUTO_CHECK_INTERVAL_MIN = "auto_check_interval_min"
    private const val KEY_DELETE_DEAD_ON_FULL_SCAN = "delete_dead_on_full_scan"
    private const val KEY_LAST_AUTO_NOTIFIED_LINK = "last_auto_notified_link"
    private const val KEY_SELECTED_SOURCE = "selected_source"
    private const val KEY_LAST_FOUND_FASTEST_LINK = "last_found_fastest_link"
    private const val KEY_REMOTE_AVAILABLE_CACHE = "remote_available_cache"
    private const val KEY_REMOTE_AVAILABLE_CACHE_TS = "remote_available_cache_ts"
    private const val KEY_REMOTE_WHITE_CACHE = "remote_white_cache"
    private const val KEY_REMOTE_WHITE_CACHE_TS = "remote_white_cache_ts"

    fun getServerList(context: Context): String {
        return prefs(context).getString(KEY_SERVER_LIST, "").orEmpty()
    }

    fun setServerList(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER_LIST, value).apply()
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

    fun getLastAutoNotifiedLink(context: Context): String {
        return prefs(context).getString(KEY_LAST_AUTO_NOTIFIED_LINK, "").orEmpty()
    }

    fun setLastAutoNotifiedLink(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_AUTO_NOTIFIED_LINK, value).apply()
    }

    fun getSelectedSource(context: Context): String {
        return prefs(context).getString(KEY_SELECTED_SOURCE, ListSource.LOCAL_MANUAL.prefValue).orEmpty()
    }

    fun setSelectedSource(context: Context, value: ListSource) {
        prefs(context).edit().putString(KEY_SELECTED_SOURCE, value.prefValue).apply()
    }

    fun getLastFoundFastestLink(context: Context): String {
        return prefs(context).getString(KEY_LAST_FOUND_FASTEST_LINK, "").orEmpty()
    }

    fun setLastFoundFastestLink(context: Context, value: String) {
        prefs(context).edit().putString(KEY_LAST_FOUND_FASTEST_LINK, value).apply()
    }

    fun getRemoteCache(context: Context, source: ListSource): String {
        return when (source) {
            ListSource.REMOTE_AVAILABLE -> prefs(context).getString(KEY_REMOTE_AVAILABLE_CACHE, "").orEmpty()
            ListSource.REMOTE_WHITE -> prefs(context).getString(KEY_REMOTE_WHITE_CACHE, "").orEmpty()
            ListSource.LOCAL_MANUAL -> ""
        }
    }

    fun setRemoteCache(context: Context, source: ListSource, value: String) {
        when (source) {
            ListSource.REMOTE_AVAILABLE -> prefs(context).edit().putString(KEY_REMOTE_AVAILABLE_CACHE, value).apply()
            ListSource.REMOTE_WHITE -> prefs(context).edit().putString(KEY_REMOTE_WHITE_CACHE, value).apply()
            ListSource.LOCAL_MANUAL -> Unit
        }
    }

    fun getRemoteCacheTimestamp(context: Context, source: ListSource): Long {
        return when (source) {
            ListSource.REMOTE_AVAILABLE -> prefs(context).getLong(KEY_REMOTE_AVAILABLE_CACHE_TS, 0L)
            ListSource.REMOTE_WHITE -> prefs(context).getLong(KEY_REMOTE_WHITE_CACHE_TS, 0L)
            ListSource.LOCAL_MANUAL -> 0L
        }
    }

    fun setRemoteCacheTimestamp(context: Context, source: ListSource, value: Long) {
        when (source) {
            ListSource.REMOTE_AVAILABLE -> prefs(context).edit().putLong(KEY_REMOTE_AVAILABLE_CACHE_TS, value).apply()
            ListSource.REMOTE_WHITE -> prefs(context).edit().putLong(KEY_REMOTE_WHITE_CACHE_TS, value).apply()
            ListSource.LOCAL_MANUAL -> Unit
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
