package com.example.farolestrela

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.ParcelUuid
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
class BleScanService : Service() {

    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothAdapter: BluetoothAdapter? = null

    companion object {
        const val SERVICE_CHANNEL_ID = "star_iot_service_channel_v2"
        const val ALERT_CHANNEL_ID_SOUND = "health_alert_channel_sound_v1"
        const val ALERT_CHANNEL_ID_MUTE = "health_alert_channel_mute_v1"
        const val FOREGROUND_NOTIFICATION_ID = 1001
        const val ALERT_NOTIFICATION_ID = 1002
        const val DEBUG_LAST_SCANNED_ID = 8881
        const val DEBUG_LAST_POPUP_ID = 8882
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        SettingsPrefs.clearLastShownAlert(this)
        CapRepository.init(this)

        val prefs = SettingsPrefs.getPrefs(this)
        if (prefs.getBoolean(SettingsPrefs.KEY_DEBUG_NOTIFICATIONS_ENABLED, false)) {
            DebugLogger.updateLastPopupDebugNotification(this, "None", "No popup shown yet", "None")
        } else {
            try {
                NotificationManagerCompat.from(this).cancel("debug_popup", DEBUG_LAST_POPUP_ID)
                NotificationManagerCompat.from(this).cancel(DEBUG_LAST_POPUP_ID)
            } catch (_: Exception) {}
        }

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(FOREGROUND_NOTIFICATION_ID, createForegroundNotification())
        startScanning()
        return START_STICKY
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Star IoT Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Star IoT running in the background"
            }
            notificationManager.createNotificationChannel(serviceChannel)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val soundChannel = NotificationChannel(
                ALERT_CHANNEL_ID_SOUND,
                "Health Hazard Alerts (Sound)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts with sound when a health hazard beacon is scanned"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000)
                enableLights(true)
                setSound(alarmUri, AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build())
            }
            notificationManager.createNotificationChannel(soundChannel)

            val muteChannel = NotificationChannel(
                ALERT_CHANNEL_ID_MUTE,
                "Health Hazard Alerts (Muted)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts without sound when a health hazard beacon is scanned"
                enableVibration(true)
                enableLights(true)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(muteChannel)
        }
    }

    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("Star IoT Active")
            .setContentText("Star IoT is monitoring for nearby health hazards")
            .setSmallIcon(R.drawable.ic_health_alert)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            parseScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            super.onBatchScanResults(results)
            for (result in results) {
                parseScanResult(result)
            }
        }
    }

    private fun startScanning() {
        try {
            val prefs = SettingsPrefs.getPrefs(this)
            val targetUuidStr = prefs.getString(SettingsPrefs.KEY_SERVICE_UUID, "1234") ?: "1234"

            val parcelUuid = try {
                if (targetUuidStr.length == 4) {
                    ParcelUuid.fromString("0000$targetUuidStr-0000-1000-8000-00805F9B34FB")
                } else {
                    ParcelUuid.fromString(targetUuidStr)
                }
            } catch (e: Exception) {
                null
            }

            val filters = if (parcelUuid != null) {
                listOf(
                    ScanFilter.Builder().setServiceUuid(parcelUuid).build(),
                    ScanFilter.Builder().setServiceData(parcelUuid, null, null).build()
                )
            } else {
                null
            }

            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .build()

            bluetoothLeScanner?.startScan(filters, scanSettings, scanCallback)
        } catch (e: Exception) {
            try {
                val scanSettings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .build()
                bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
            } catch (ex: Exception) {
                // Ignore
            }
        }
    }

    private fun parseScanResult(result: ScanResult) {
        val eval = AlertEvaluator.evaluate(this, result) ?: return

        val prefs = SettingsPrefs.getPrefs(this)
        val debugEnabled = prefs.getBoolean(SettingsPrefs.KEY_DEBUG_NOTIFICATIONS_ENABLED, false)
        val notificationManager = NotificationManagerCompat.from(this)

        if (debugEnabled) {
            updateLastScannedDebugNotification(
                eval.deviceName, eval.advertisedText, eval.rssi, eval.address,
                eval.passesRssi, eval.hasServiceUuidTarget, eval.isDuplicate, eval.isForeground, eval.willShowPopup
            )
        } else {
            try {
                notificationManager.cancel("debug_scanned", DEBUG_LAST_SCANNED_ID)
                notificationManager.cancel(DEBUG_LAST_SCANNED_ID)
                notificationManager.cancel("debug_popup", DEBUG_LAST_POPUP_ID)
                notificationManager.cancel(DEBUG_LAST_POPUP_ID)
            } catch (_: Exception) {}
        }

        // 100% UNIFIED POPUP LOGIC: Always launch AlertActivity via startActivity whenever willShowPopup is true (both in-app and idle)
        if (eval.passesRssi) {
            val beaconItem = BeaconItem(
                address = eval.address,
                name = eval.deviceName,
                rssi = eval.rssi,
                advertisedText = eval.advertisedText,
                timestamp = System.currentTimeMillis(),
                btDeviceName = eval.btDeviceName,
                advDeviceName = eval.advDeviceName,
                serviceUuids = eval.serviceUuids,
                serviceData = eval.serviceData,
                manufacturerData = eval.manufacturerData
            )
            BeaconRepository.addOrUpdateBeacon(beaconItem)

            if (eval.willShowPopup) {
                if (debugEnabled) {
                    val triggerSource = if (eval.isForeground) "Triggered (Foreground)" else "Triggered (Idle)"
                    DebugLogger.updateLastPopupDebugNotification(this, eval.deviceName, eval.advertisedText, triggerSource)
                }

                val intent = Intent(this, AlertActivity::class.java).apply {
                    putExtra("BEACON_NAME", eval.deviceName)
                    putExtra("BEACON_TEXT", eval.advertisedText)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                val pendingIntent = PendingIntent.getActivity(
                    this, System.currentTimeMillis().toInt(), intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )

                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignore
                }

                val soundEnabled = prefs.getBoolean(SettingsPrefs.KEY_SOUND_ENABLED, true)
                val channelId = if (soundEnabled) ALERT_CHANNEL_ID_SOUND else ALERT_CHANNEL_ID_MUTE

                val notificationBuilder = NotificationCompat.Builder(this, channelId)
                    .setContentTitle("Alerta de Saúde: ${eval.deviceName}")
                    .setContentText(eval.advertisedText)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(eval.advertisedText))
                    .setSmallIcon(R.drawable.ic_health_alert)
                    .setPriority(NotificationCompat.PRIORITY_MAX)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setFullScreenIntent(pendingIntent, true)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)

                if (soundEnabled) {
                    val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    notificationBuilder.setSound(alarmUri)
                    notificationBuilder.setVibrate(longArrayOf(0, 1000, 500, 1000))
                } else {
                    notificationBuilder.setSound(null)
                }

                try {
                    val tag = "health_alert_${System.currentTimeMillis()}"
                    NotificationManagerCompat.from(this).notify(tag, ALERT_NOTIFICATION_ID, notificationBuilder.build())
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    private fun updateLastScannedDebugNotification(
        deviceName: String,
        advertisedText: String,
        rssi: Int,
        address: String,
        passesRssi: Boolean,
        hasUuid: Boolean,
        isDuplicate: Boolean,
        isForeground: Boolean,
        willShowPopup: Boolean
    ) {
        val timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val text = "Title: $deviceName | Text: $advertisedText\nRSSI: $rssi (Pass: $passesRssi) | MAC: $address\nUUID Target: $hasUuid | IsDuplicate: $isDuplicate\nForeground: $isForeground | Will Show Popup: $willShowPopup\nTime: $timeFormatted"

        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID_MUTE)
            .setContentTitle("[Debug] Last Scanned Beacon")
            .setContentText("Show Popup: $willShowPopup | IsDuplicate: $isDuplicate")
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_health_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            NotificationManagerCompat.from(this).notify("debug_scanned", DEBUG_LAST_SCANNED_ID, notification)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
