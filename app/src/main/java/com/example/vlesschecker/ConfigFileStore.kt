package com.example.vlesschecker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

data class SavedConfigSnapshot(
    val file: File,
    val lineCount: Int,
    val source: ListSource,
    val workingOnly: Boolean
)

object ConfigFileStore {
    private const val DIR_NAME = "saved_config_lists"

    fun saveCurrentSnapshot(context: Context, source: ListSource, rawText: String): SavedConfigSnapshot? {
        val lines = VlessChecker.normalizeLines(rawText)
        return saveLines(context, source, lines, workingOnly = false)
    }

    fun saveWorkingSnapshot(context: Context, source: ListSource, links: List<String>): SavedConfigSnapshot? {
        val lines = links.mapNotNull { VlessChecker.canonicalizeSupportedLink(it) }
        return saveLines(context, source, lines, workingOnly = true)
    }

    fun getCurrentSnapshotFile(context: Context, source: ListSource): File {
        return File(snapshotDir(context), fileName(source, workingOnly = false))
    }

    fun getWorkingSnapshotFile(context: Context, source: ListSource): File {
        return File(snapshotDir(context), fileName(source, workingOnly = true))
    }

    fun saveDisplayedSnapshot(context: Context, source: ListSource, links: List<String>): SavedConfigSnapshot? {
        val lines = links.mapNotNull { VlessChecker.canonicalizeSupportedLink(it) }
        return saveLines(context, source, lines, workingOnly = true)
    }

    fun shareCurrentSnapshot(context: Context, source: ListSource): Boolean {
        val file = getCurrentSnapshotFile(context, source)
        if (!file.exists() || file.length() == 0L) return false
        return shareFile(context, file, context.getString(R.string.share_source_file_subject))
    }

    fun shareWorkingSnapshot(context: Context, source: ListSource): Boolean {
        val file = getWorkingSnapshotFile(context, source)
        if (!file.exists() || file.length() == 0L) return false
        return shareFile(context, file, context.getString(R.string.share_working_file_subject))
    }

    private fun saveLines(
        context: Context,
        source: ListSource,
        lines: List<String>,
        workingOnly: Boolean
    ): SavedConfigSnapshot? {
        val normalized = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val file = if (workingOnly) getWorkingSnapshotFile(context, source) else getCurrentSnapshotFile(context, source)
        if (normalized.isEmpty()) {
            if (file.exists()) file.delete()
            return null
        }
        snapshotDir(context).mkdirs()
        file.writeText(normalized.joinToString("\n") + "\n", Charsets.UTF_8)
        return SavedConfigSnapshot(file, normalized.size, source, workingOnly)
    }

    private fun shareFile(context: Context, file: File, subject: String): Boolean {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            val chooser = Intent.createChooser(intent, context.getString(R.string.share_file_chooser_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    private fun snapshotDir(context: Context): File {
        return File(context.filesDir, DIR_NAME)
    }

    private fun fileName(source: ListSource, workingOnly: Boolean): String {
        val suffix = if (workingOnly) "working" else "current"
        return when (source) {
            is ListSource.Manual -> "manual_${suffix}.txt"
            is ListSource.XrayAvailable -> "xray_available_top100_${suffix}.txt"
            is ListSource.XrayWhitelist -> "xray_whitelist_top100_${suffix}.txt"
            is ListSource.UserDefined -> "user_defined_${suffix}.txt"
        }
    }
}
