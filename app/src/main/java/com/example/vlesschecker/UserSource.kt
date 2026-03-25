package com.example.vlesschecker

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Represents a user-defined remote source (URL).
 */
data class UserSource(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var url: String,
    var lastUpdated: Long = 0L,
    var cachedContent: String = "",
    var isEnabled: Boolean = true
) {
    fun displayName(context: Context): String {
        return if (name.isNotBlank()) name else url.take(50)
    }
}

/**
 * Manages user-defined sources stored in SharedPreferences.
 */
object UserSourceManager {
    private const val PREFS_KEY = "user_sources"
    private val gson = Gson()
    private val typeToken = object : TypeToken<List<UserSource>>() {}.type

    fun getAll(context: Context): List<UserSource> {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        return try {
            gson.fromJson(json, typeToken) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveAll(context: Context, sources: List<UserSource>) {
        val prefs = context.getSharedPreferences(AppPrefs.PREFS_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(sources)
        prefs.edit().putString(PREFS_KEY, json).apply()
    }

    fun add(context: Context, source: UserSource) {
        val sources = getAll(context).toMutableList()
        sources.add(source)
        saveAll(context, sources)
    }

    fun update(context: Context, source: UserSource) {
        val sources = getAll(context).toMutableList()
        val index = sources.indexOfFirst { it.id == source.id }
        if (index >= 0) {
            sources[index] = source
            saveAll(context, sources)
        }
    }

    fun delete(context: Context, sourceId: String) {
        val sources = getAll(context).toMutableList()
        sources.removeAll { it.id == sourceId }
        saveAll(context, sources)
    }

    fun getById(context: Context, id: String): UserSource? {
        return getAll(context).firstOrNull { it.id == id }
    }
}