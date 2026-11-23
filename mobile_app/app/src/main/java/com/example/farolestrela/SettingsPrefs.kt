package com.example.farolestrela

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit

object SettingsPrefs {
    private const val PREFS_NAME = "star_iot_prefs"
    const val KEY_DUPLICATE_ENABLED = "duplicate_detection"
    const val KEY_DUPLICATE_WINDOW = "duplicate_window"
    const val KEY_RSSI_THRESHOLD = "rssi_threshold"
    const val KEY_SOUND_ENABLED = "sound_enabled"
    const val KEY_RSSI_FILTER_ENABLED = "rssi_filter_enabled"
    const val KEY_POPUP_WARNINGS = "popup_warnings_enabled"
    const val KEY_DEBUG_NOTIFICATIONS_ENABLED = "debug_notifications_enabled"
    const val KEY_SERVICE_UUID = "service_uuid"
    private const val KEY_LAST_SHOWN_ALERT = "last_shown_alert_key"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveAlertTimestamp(context: Context, alertKey: String, timestamp: Long) {
        getPrefs(context).edit().putLong("alert_$alertKey", timestamp).commit()
    }

    fun getAlertTimestamp(context: Context, alertKey: String): Long {
        return getPrefs(context).getLong("alert_$alertKey", 0L)
    }

    fun clearAlertTimestamps(context: Context) {
        val prefs = getPrefs(context)
        val editor = prefs.edit()
        for ((key, _) in prefs.all) {
            if (key.startsWith("alert_")) {
                editor.remove(key)
            }
        }
        editor.commit()
    }

    fun saveLastShownAlert(context: Context, alertKey: String) {
        Log.d("SettingsPrefs", "Last Shown Alert Saved: $alertKey")
        getPrefs(context).edit().putString(KEY_LAST_SHOWN_ALERT, alertKey).commit()
    }

    fun getLastShownAlert(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_SHOWN_ALERT, null)
    }

    fun clearLastShownAlert(context: Context) {
        getPrefs(context).edit(commit = true) { remove(KEY_LAST_SHOWN_ALERT) }
        clearAlertTimestamps(context)
    }
}
