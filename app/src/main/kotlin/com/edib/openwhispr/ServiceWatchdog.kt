package com.edib.openwhispr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Worker
import java.util.concurrent.TimeUnit

/**
 * Watches the accessibility service. The OS can kill the process, or a battery manager
 * can freeze it, while the Accessibility toggle stays on. Then nothing runs and nothing
 * says so. A periodic check posts a notification pointing to Accessibility settings.
 * Android doesn't let an app switch its own accessibility service back on, so the user
 * still has to toggle it.
 */
object ServiceWatchdog {
    private const val WORK_NAME = "openwispr_service_watchdog"
    private const val CHANNEL_ID = "openwhispr_watchdog"
    private const val NOTIF_ID = 4201
    private const val SETTLE_MS = 5_000L

    /** True when [enabledServices] (Settings.Secure value) lists [component] (flattened). */
    fun containsComponent(enabledServices: String?, component: String): Boolean =
        enabledServices?.split(':')?.any { it.equals(component, ignoreCase = true) } == true

    fun isEnabledInSettings(context: Context): Boolean = containsComponent(
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        ComponentName(context, WhisperAccessibilityService::class.java).flattenToString(),
    )

    /** Schedules the periodic check. Safe to call on every launch: the existing schedule is kept. */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<Check>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    class Check(context: Context, params: WorkerParameters) : Worker(context, params) {
        override fun doWork(): Result {
            val context = applicationContext
            val paused = context.getSharedPreferences("openwispr", Context.MODE_PRIVATE)
                .getBoolean("service_master_enabled", true).not()
            if (paused || !isEnabledInSettings(context)) return Result.success()

            // A freshly started process gets its accessibility connection a moment after launch.
            Thread.sleep(SETTLE_MS)
            if (WhisperAccessibilityService.instance != null) {
                NotificationManagerCompat.from(context).cancel(NOTIF_ID)
            } else {
                notifyNotRunning(context)
            }
            return Result.success()
        }

        private fun notifyNotRunning(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.watchdog_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(context.getString(R.string.watchdog_title))
                .setContentText(context.getString(R.string.watchdog_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
            } catch (_: SecurityException) {
                // Notification permission not granted; the Status tab still shows the state.
            }
        }
    }
}
