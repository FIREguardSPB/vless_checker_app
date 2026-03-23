package com.example.vlesschecker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object ConfigHandoffHelper {

    fun handoffToCompatibleApp(activity: Activity, link: String): Boolean {
        val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, link)
        }

        val packageManager = activity.packageManager
        return when {
            viewIntent.resolveActivity(packageManager) != null -> {
                activity.startActivity(viewIntent)
                true
            }
            sendIntent.resolveActivity(packageManager) != null -> {
                val chooser = Intent.createChooser(sendIntent, activity.getString(R.string.handoff_chooser_title))
                activity.startActivity(chooser)
                true
            }
            else -> false
        }
    }

    fun openVpnSettings(activity: Activity): Boolean {
        val intent = Intent(Settings.ACTION_VPN_SETTINGS)
        return if (intent.resolveActivity(activity.packageManager) != null) {
            activity.startActivity(intent)
            true
        } else {
            false
        }
    }
}
