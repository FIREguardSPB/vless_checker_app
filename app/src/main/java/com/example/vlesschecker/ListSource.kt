package com.example.vlesschecker

import android.content.Context

/**
 * Источник списка конфигов.
 * 
 * Sealed class поддерживает статические источники и пользовательские (с ID и названием).
 */
sealed class ListSource(val prefValue: String) {
    abstract fun displayName(context: Context): String

    /** Локальный ручной ввод */
    object Manual : ListSource("manual") {
        override fun displayName(context: Context) = context.getString(R.string.source_manual_label)
    }

    /** Удалённый список: XRay Available ST Top100 */
    object XrayAvailable : ListSource("xray_available_st_top100") {
        override fun displayName(context: Context) = context.getString(R.string.source_xray_available_label)
    }

    /** Удалённый список: XRay Whitelist ST Top100 */
    object XrayWhitelist : ListSource("xray_white_list_st_top100") {
        override fun displayName(context: Context) = context.getString(R.string.source_xray_whitelist_label)
    }

    /** Пользовательский источник (URL) */
    data class UserDefined(
        val id: String,
        val name: String,
        val url: String,
        val isEnabled: Boolean = true
    ) : ListSource("user_defined:$id") {
        override fun displayName(context: Context) = name
    }

    companion object {
        /**
         * Преобразует строковое значение из SharedPreferences в ListSource.
         * Поддерживает старый формат (просто "user_defined") для обратной совместимости.
         */
        fun fromPrefValue(context: Context, value: String?): ListSource {
            return when (value) {
                "manual" -> Manual
                "xray_available_st_top100" -> XrayAvailable
                "xray_white_list_st_top100" -> XrayWhitelist
                else -> {
                    if (value?.startsWith("user_defined:") == true) {
                        val id = value.substringAfter("user_defined:")
                        val source = UserSourceManager.getById(context, id)
                        if (source != null) {
                            UserDefined(
                                id = source.id,
                                name = source.name,
                                url = source.url,
                                isEnabled = source.isEnabled
                            )
                        } else {
                            // Fallback: старый источник без ID
                            Manual
                        }
                    } else if (value == "user_defined") {
                        // Старый формат (до версии с множественными источниками)
                        migrateOldUserSource(context)
                        val enabledSources = UserSourceManager.getEnabledSources(context)
                        if (enabledSources.isNotEmpty()) {
                            val source = enabledSources.first()
                            UserDefined(
                                id = source.id,
                                name = source.name,
                                url = source.url,
                                isEnabled = source.isEnabled
                            )
                        } else {
                            Manual
                        }
                    } else {
                        Manual
                    }
                }
            }
        }

        /**
         * Получить все доступные источники для отображения в Spinner.
         */
        fun getAllSources(context: Context): List<ListSource> {
            val staticSources = listOf(Manual, XrayAvailable, XrayWhitelist)
            val userSources = UserSourceManager.getEnabledSources(context).map { source ->
                UserDefined(
                    id = source.id,
                    name = source.name,
                    url = source.url,
                    isEnabled = source.isEnabled
                )
            }
            return staticSources + userSources
        }

        /**
         * Получить только статические источники (без пользовательских).
         */
        fun getStaticSources(): List<ListSource> = listOf(Manual, XrayAvailable, XrayWhitelist)

        /**
         * Миграция старого пользовательского URL в новую систему.
         */
        private fun migrateOldUserSource(context: Context) {
            val oldUrl = context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                .getString("user_defined_url", "") ?: ""
            if (oldUrl.isNotBlank()) {
                val oldName = context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
                    .getString("user_defined_name", "") ?: ""
                
                val source = UserSource(
                    name = if (oldName.isNotBlank()) oldName else "Импортированный источник",
                    url = oldUrl,
                    isEnabled = true
                )
                UserSourceManager.add(context, source)
                
                // Очищаем старые ключи
                context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove("user_defined_url")
                    .remove("user_defined_name")
                    .apply()
            }
        }
    }
}