package com.watermonitor.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.watermonitor.app.MainActivity
import com.watermonitor.app.R
import com.watermonitor.app.utils.LocaleHelper

internal object TankNotifications {
    const val MONITOR_ID = 1001
    const val FULL_ID = 1002
    const val LEAK_ID = 1003
    const val ALERT_CHANNEL = "tank_full"
    const val LEAK_CHANNEL = "leak_detected"
    private const val MONITOR_CHANNEL = "tank_monitoring"
    private val ALERT_VIBRATION = longArrayOf(0, 500, 200, 500, 200, 800)

    fun createChannels(context: Context) {
        val localized = LocaleHelper.wrap(context)
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(
            listOf(
                NotificationChannel(MONITOR_CHANNEL,
                    localized.getString(R.string.tank_monitor_channel), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(ALERT_CHANNEL,
                    localized.getString(R.string.tank_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = localized.getString(R.string.tank_alert_channel_description)
                    enableVibration(true)
                    vibrationPattern = ALERT_VIBRATION
                },
                NotificationChannel(LEAK_CHANNEL,
                    localized.getString(R.string.leak_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = localized.getString(R.string.leak_alert_channel_description)
                    enableVibration(true)
                    vibrationPattern = ALERT_VIBRATION
                }
            )
        )
    }

    fun canNotify(context: Context, channel: String): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun canMonitor(context: Context): Boolean =
        canNotify(context, ALERT_CHANNEL) || canNotify(context, LEAK_CHANNEL)

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun monitoring(context: Context): Notification {
        val localized = LocaleHelper.wrap(context)
        return NotificationCompat.Builder(context, MONITOR_CHANNEL)
            .setSmallIcon(R.drawable.ic_tank_notification)
            .setContentTitle(localized.getString(R.string.tank_monitor_title))
            .setContentText(localized.getString(R.string.tank_monitor_message))
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun showFull(context: Context): Boolean = showAlert(
        context, ALERT_CHANNEL, FULL_ID, R.string.tank_full_title, R.string.tank_full_message)

    fun showLeak(context: Context): Boolean = showAlert(
        context, LEAK_CHANNEL, LEAK_ID, R.string.leak_alert_title, R.string.leak_alert_message)

    private fun showAlert(context: Context, channel: String, id: Int, title: Int, message: Int): Boolean {
        if (!canNotify(context, channel)) return false
        val localized = LocaleHelper.wrap(context)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tank_notification)
            .setContentTitle(localized.getString(title))
            .setContentText(localized.getString(message))
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                localized.getString(message)))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVibrate(ALERT_VIBRATION)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        return try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
            true
        } catch (_: SecurityException) {
            false // Permission may be revoked between checking it and posting.
        }
    }

    fun cancelFull(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(FULL_ID)
    }

    fun cancelLeak(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(LEAK_ID)
    }

    fun cancelAlerts(context: Context) {
        cancelFull(context)
        cancelLeak(context)
    }
}
