package com.example.vlesschecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Сервис для фоновой проверки конфигов с отображением прогресса в уведомлении.
 * Продолжает работу даже при закрытом приложении.
 */
class ForegroundCheckingService : Service() {
    companion object {
        private const val TAG = "ForegroundChecking"
        private const val NOTIFICATION_CHANNEL_ID = "checking_channel"
        private const val NOTIFICATION_ID = 101
        private const val ACTION_STOP = "com.example.vlesschecker.action.STOP"

        private const val EXTRA_CONFIGS = "configs"
        private const val EXTRA_SOURCE = "source"

        /**
         * Запустить сервис проверки.
         */
        fun start(context: Context, configs: List<String>, source: ListSource) {
            val intent = Intent(context, ForegroundCheckingService::class.java).apply {
                putStringArrayListExtra(EXTRA_CONFIGS, ArrayList(configs))
                putExtra(EXTRA_SOURCE, source.prefValue)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Остановить сервис.
         */
        fun stop(context: Context) {
            val intent = Intent(context, ForegroundCheckingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationManager: NotificationManager
    private var isChecking = false
    private var totalConfigs = 0
    private var checkedCount = 0
    private var successfulCount = 0

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_STOP -> {
                stopChecking()
                return START_NOT_STICKY
            }
            else -> {
                if (isChecking) {
                    Log.w(TAG, "Already checking, ignoring new request")
                    return START_NOT_STICKY
                }
                val configs = intent?.getStringArrayListExtra(EXTRA_CONFIGS) ?: emptyList()
                val sourcePref = intent?.getStringExtra(EXTRA_SOURCE) ?: ListSource.Manual.prefValue
                val source = ListSource.fromPrefValue(this, sourcePref) ?: ListSource.Manual
                startChecking(configs, source)
            }
        }
        return START_NOT_STICKY
    }

    private fun startChecking(configs: List<String>, source: ListSource) {
        if (configs.isEmpty()) {
            Log.w(TAG, "No configs to check")
            stopSelf()
            return
        }

        isChecking = true
        totalConfigs = configs.size
        checkedCount = 0
        successfulCount = 0

        // Start foreground with initial notification
        val notification = buildNotification(
            progress = 0,
            indeterminate = true,
            title = "Начата проверка конфигов"
        )
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            val results = mutableListOf<LinkCheckResult>()
            configs.forEachIndexed { index, link ->
                if (!isChecking) return@forEachIndexed

                val result = VlessChecker.checkSingle(this@ForegroundCheckingService, link)
                results.add(result)

                checkedCount = index + 1
                if (result.success) {
                    successfulCount++
                }

                // Update notification every 5 configs or if last
                if (index % 5 == 0 || index == configs.lastIndex) {
                    updateNotificationProgress()
                }
            }

            if (isChecking) {
                // Save results
                PersistentWorkingConfigsManager.updateWithNewResults(
                    context = this@ForegroundCheckingService,
                    newResults = results,
                    source = source
                )

                // Final notification
                val finalNotification = buildNotification(
                    progress = 100,
                    indeterminate = false,
                    title = "Проверка завершена",
                    text = "Успешно: $successfulCount/$totalConfigs"
                )
                notificationManager.notify(NOTIFICATION_ID, finalNotification)

                // Broadcast completion
                sendBroadcast(
                    Intent(ACTION_CHECKING_COMPLETED).apply {
                        putExtra("successful", successfulCount)
                        putExtra("total", totalConfigs)
                    }
                )
            }

            // Stop service after delay
            launch(Dispatchers.Main) {
                Thread.sleep(3000) // Show final notification for 3 seconds
                stopSelf()
            }
        }
    }

    private fun stopChecking() {
        isChecking = false
        serviceScope.coroutineContext.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotificationProgress() {
        val progress = if (totalConfigs > 0) {
            (checkedCount * 100 / totalConfigs).coerceIn(0, 100)
        } else 0
        val notification = buildNotification(
            progress = progress,
            indeterminate = false,
            title = "Проверка конфигов",
            text = "Проверено: $checkedCount/$totalConfigs"
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
        
        // Send progress broadcast
        sendBroadcast(
            Intent(ACTION_CHECKING_PROGRESS).apply {
                putExtra("checked", checkedCount)
                putExtra("total", totalConfigs)
            }
        )
    }

    private fun buildNotification(
        progress: Int,
        indeterminate: Boolean,
        title: String,
        text: String? = null
    ): Notification {
        val stopIntent = Intent(this, ForegroundCheckingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openAppPendingIntent)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                stopPendingIntent
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Проверка конфигов",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Показывает прогресс проверки прокси‑конфигов"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

// Broadcast action for completion
const val ACTION_CHECKING_COMPLETED = "com.example.vlesschecker.action.CHECKING_COMPLETED"
const val ACTION_CHECKING_PROGRESS = "com.example.vlesschecker.action.CHECKING_PROGRESS"