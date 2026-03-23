package com.example.vlesschecker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object VpnImportHelper {

    private const val V2RAY_TUN_PACKAGE = "com.v2raytun.android"

    enum class ControlAction(val path: String) {
        START("start"),
        STOP("stop"),
        RESTART("restart")
    }

    fun prepareClipboardConfig(link: String): String {
        return link.trim()
    }

    fun importOrShare(context: Context, link: String): Boolean {
        val prepared = prepareClipboardConfig(link)

        val packageIntent = Intent(Intent.ACTION_VIEW, Uri.parse(prepared)).apply {
            setPackage(V2RAY_TUN_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(packageIntent)
            return true
        } catch (_: ActivityNotFoundException) {
            // Fall through to generic handler.
        }

        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(prepared)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(viewIntent)
            true
        } catch (_: ActivityNotFoundException) {
            shareLink(context, prepared)
            false
        }
    }

    fun shareLink(context: Context, link: String) {
        val prepared = prepareClipboardConfig(link)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_fastest_subject))
            putExtra(Intent.EXTRA_TEXT, prepared)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, context.getString(R.string.share_fastest_chooser))
            .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(chooser)
    }

    fun controlV2RayTun(context: Context, action: ControlAction): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("v2raytun://control/${action.path}")).apply {
            setPackage(V2RAY_TUN_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun openVpnSettings(context: Context) {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
