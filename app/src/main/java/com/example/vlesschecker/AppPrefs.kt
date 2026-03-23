package com.example.vlesschecker

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "vless_checker_prefs"
    private const val KEY_SERVER_LIST = "server_list"
    private const val KEY_AUTO_CHECK_ENABLED = "auto_check_enabled"
    private const val KEY_AUTO_CHECK_INTERVAL_MIN = "auto_check_interval_min"
    private const val KEY_DELETE_DEAD_ON_FULL_SCAN = "delete_dead_on_full_scan"
    private const val KEY_LAST_AUTO_NOTIFIED_LINK = "last_auto_notified_link"

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

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
