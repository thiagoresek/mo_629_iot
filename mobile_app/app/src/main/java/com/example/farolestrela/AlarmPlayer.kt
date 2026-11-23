package com.example.farolestrela

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

object AlarmPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun playAlarm(context: Context) {
        val prefs = SettingsPrefs.getPrefs(context)
        val soundEnabled = prefs.getBoolean(SettingsPrefs.KEY_SOUND_ENABLED, true)
        if (!soundEnabled) return

        try {
            stopAlarm()
            val soundFile = SoundGenerator.getOrCreateAlarmSoundFile(context)
            val uri = if (soundFile.exists()) {
                Uri.fromFile(soundFile)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            try {
                val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                ringtone?.play()
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    fun stopAlarm() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            mediaPlayer = null
        }
    }
}
