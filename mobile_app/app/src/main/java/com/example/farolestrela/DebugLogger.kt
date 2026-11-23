package com.example.farolestrela

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
object DebugLogger {
    fun updateLastPopupDebugNotification(context: Context, deviceName: String, advertisedText: String, source: String) {
        val prefs = SettingsPrefs.getPrefs(context)
        val enabled = prefs.getBoolean(SettingsPrefs.KEY_DEBUG_NOTIFICATIONS_ENABLED, false)
        val notificationManager = NotificationManagerCompat.from(context)

        if (!enabled) {
            try {
                notificationManager.cancel("debug_popup", BleScanService.DEBUG_LAST_POPUP_ID)
                notificationManager.cancel(BleScanService.DEBUG_LAST_POPUP_ID)
            } catch (_: Exception) {}
            return
        }

        val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val text = "Title: $deviceName\nText: $advertisedText\nTrigger: $source\nTime: $timeFormatted"
        val notification = NotificationCompat.Builder(context, BleScanService.ALERT_CHANNEL_ID_MUTE)
            .setContentTitle("[Debug] Last Popup Shown")
            .setContentText("Title: $deviceName | Trigger: $source")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_health_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            notificationManager.notify("debug_popup", BleScanService.DEBUG_LAST_POPUP_ID, notification)
        } catch (_: Exception) {
            // Ignore
        }
    }
}
