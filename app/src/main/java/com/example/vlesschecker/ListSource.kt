package com.example.vlesschecker

import android.content.Context

enum class ListSource(val prefValue: String) {
    MANUAL("manual"),
    XRAY_AVAILABLE_ST_TOP100("xray_available_st_top100"),
    XRAY_WHITE_LIST_ST_TOP100("xray_white_list_st_top100");

    fun displayName(context: Context): String {
        return when (this) {
            MANUAL -> context.getString(R.string.source_manual_label)
            XRAY_AVAILABLE_ST_TOP100 -> context.getString(R.string.source_xray_available_label)
            XRAY_WHITE_LIST_ST_TOP100 -> context.getString(R.string.source_xray_whitelist_label)
        }
    }

    companion object {
        fun fromPrefValue(value: String?): ListSource {
            return values().firstOrNull { it.prefValue == value } ?: MANUAL
        }
    }
}
