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
        return try {
            NotificationHelper.createChannel(applicationContext)

            val selectedSource = AppPrefs.getSelectedSource(applicationContext)
            val sourceResult = withContext(Dispatchers.IO) {
                RemoteListRepository.loadForSource(
                    context = applicationContext,
                    source = selectedSource,
                    preferFreshRemote = selectedSource != ListSource.Manual
                )
            }

            val links = sourceResult.links
            if (links.isEmpty()) return Result.success()

            val result = withContext(Dispatchers.IO) {
                VlessChecker.findFastestAvailable(links)
            }

            if (result == null) {
                AppPrefs.setLastAutoNotifiedLink(applicationContext, "")
                return Result.success()
            }

            val lastLink = AppPrefs.getLastAutoNotifiedLink(applicationContext)
            if (lastLink == result.link) {
                return Result.success()
            }

            val prepared = VpnImportHelper.prepareClipboardConfig(result.link)
            ClipboardHelper.copyLink(applicationContext, prepared)
            AppPrefs.setLastFastestLink(applicationContext, prepared)
            NotificationHelper.showFoundLinkNotification(
                context = applicationContext,
                link = result.link,
                title = applicationContext.getString(R.string.notification_title_auto),
                text = applicationContext.getString(
                    R.string.notification_fastest_text_with_source,
                    result.latencyMs,
                    sourceResult.sourceLabel,
                    NotificationHelper.shorten(result.link, 48)
                )
            )
            AppPrefs.setLastAutoNotifiedLink(applicationContext, result.link)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
