package com.example.vlesschecker

enum class ListSource(val prefValue: String, val isRemote: Boolean) {
    LOCAL_MANUAL("local_manual", false),
    REMOTE_AVAILABLE("remote_available", true),
    REMOTE_WHITE("remote_white", true);

    companion object {
        fun fromPref(value: String?): ListSource {
            return entries.firstOrNull { it.prefValue == value } ?: LOCAL_MANUAL
        }
    }
}
