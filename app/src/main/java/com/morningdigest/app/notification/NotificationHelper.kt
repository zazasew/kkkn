package com.morningdigest.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.morningdigest.app.MainActivity
import com.morningdigest.app.data.model.CustomAlertMatch
import com.morningdigest.app.data.model.DigestReport

object NotificationHelper {
    const val CHANNEL_ID = "morning_digest_brief"
    private const val CHANNEL_NAME = "Morning Digest"
    // Separate, higher-importance channel for custom weather alert heads-ups
    // ("temperature about to cross your limit", etc.) - these are time-sensitive
    // and shouldn't be silently bundled with (or muted alongside) the daily brief.
    const val CHANNEL_ID_WEATHER_ALERTS = "morning_digest_weather_alerts"
    private const val CHANNEL_NAME_WEATHER_ALERTS = "Custom Weather Alerts"
    private const val NOTIFICATION_ID_DIGEST = 2001
    private const val NOTIFICATION_ID_FAILURE = 2002
    private const val NOTIFICATION_ID_CUSTOM_ALERT = 2003

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Delivers your morning brief — weather, markets, and top news"
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ID_WEATHER_ALERTS,
                CHANNEL_NAME_WEATHER_ALERTS,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Heads-up warnings when your custom weather alert rules are about to be crossed"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
            manager?.createNotificationChannel(alertChannel)
        }
    }

    /**
     * Posts the digest itself as the notification — this IS the delivery
     * mechanism now (no email involved). Returns true if it was actually
     * posted (false if notification permission isn't granted).
     */
    fun postDigest(context: Context, report: DigestReport, cityLabel: String, userName: String): Boolean {
        if (!hasPermission(context)) return false

        val intent = android.content.Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = DigestNotificationBuilder.title(report, userName)
        val summary = DigestNotificationBuilder.shortSummary(report)
        val fullBrief = DigestNotificationBuilder.fullBrief(report, cityLabel)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBrief))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DIGEST, notification)
        return true
    }

    /**
     * Posts a heads-up notification for one or more custom weather alert
     * rules whose matched forecast hour has just entered the configured lead
     * time (e.g. "1h before your temperature limit is reached"). Returns true
     * if it was actually posted.
     */
    fun postCustomWeatherAlert(context: Context, matches: List<CustomAlertMatch>): Boolean {
        if (matches.isEmpty() || !hasPermission(context)) return false

        val intent = android.content.Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (matches.size == 1) "⚠️ ${matches.first().label}" else "⚠️ ${matches.size} weather alerts coming up"
        val summary = matches.joinToString(" · ") { it.label }
        val fullBody = matches.joinToString("\n") { "• ${it.label} — ${it.detail}" }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID_WEATHER_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_CUSTOM_ALERT, notification)
        return true
    }

    fun notifyFailure(context: Context, error: String) {
        if (!hasPermission(context)) return
        val intent = android.content.Intent(context, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, 0, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("❌ Morning brief failed")
            .setContentText(error)
            .setStyle(NotificationCompat.BigTextStyle().bigText(error))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_FAILURE, notification)
    }

    fun hasPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
