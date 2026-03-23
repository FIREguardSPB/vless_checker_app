package com.example.vlesschecker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

object ClipboardHelper {
    fun copyLink(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("VLESS", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
            clip.description.extras = extras
        }
        clipboard.setPrimaryClip(clip)
    }
}
