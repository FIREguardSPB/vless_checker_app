package com.example.vlesschecker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AutoCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        NotificationHelper.createChannel(applicationContext)

        val rawText = AppPrefs.getServerList(applicationContext)
        val links = VlessChecker.normalizeLines(rawText)
        if (links.isEmpty()) return Result.success()

        val result = withContext(Dispatchers.IO) {
            VlessChecker.findFirstAvailable(links)
        }

        if (result == null) {
            AppPrefs.setLastAutoNotifiedLink(applicationContext, "")
            return Result.success()
        }

        val lastLink = AppPrefs.getLastAutoNotifiedLink(applicationContext)
        if (lastLink == result.link) {
            return Result.success()
        }

        ClipboardHelper.copyLink(applicationContext, result.link)
        NotificationHelper.showFoundLinkNotification(
            context = applicationContext,
            link = result.link,
            title = applicationContext.getString(R.string.notification_title_auto),
            text = NotificationHelper.shorten(result.link)
        )
        AppPrefs.setLastAutoNotifiedLink(applicationContext, result.link)
        return Result.success()
    }
}
