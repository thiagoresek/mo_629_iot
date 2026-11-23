package com.example.farolestrela

import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log

data class EvaluationResult(
    val deviceName: String,
    val advertisedText: String,
    val rssi: Int,
    val address: String,
    val passesRssi: Boolean,
    val hasServiceUuidTarget: Boolean,
    val isDuplicate: Boolean,
    val isForeground: Boolean,
    val willShowPopup: Boolean,
    val btDeviceName: String?,
    val advDeviceName: String?,
    val serviceUuids: String,
    val serviceData: String,
    val manufacturerData: String
) {
    val isSameAsLast: Boolean get() = isDuplicate
}

object AlertEvaluator {
    fun evaluate(context: Context, result: ScanResult): EvaluationResult? {
        val device = result.device
        val address = device.address ?: "Unknown MAC"
        val btDeviceName = try { device.name } catch (_: SecurityException) { null }
        val rssi = result.rssi
        val scanRecord = result.scanRecord

        val prefs = SettingsPrefs.getPrefs(context)
        val rssiThreshold = prefs.getInt(SettingsPrefs.KEY_RSSI_THRESHOLD, -80)
        val rssiFilterEnabled = prefs.getBoolean(SettingsPrefs.KEY_RSSI_FILTER_ENABLED, true)
        val duplicateEnabled = prefs.getBoolean(SettingsPrefs.KEY_DUPLICATE_ENABLED, true)
        val duplicateWindowMins = prefs.getInt(SettingsPrefs.KEY_DUPLICATE_WINDOW, 10)
        val targetUuidStr = prefs.getString(SettingsPrefs.KEY_SERVICE_UUID, "1234") ?: "1234"
        val canonicalTarget = normalizeUuid(targetUuidStr)

        val passesRssi = if (rssiFilterEnabled) rssi >= rssiThreshold else true

        val hasServiceUuidTarget = 
            scanRecord?.serviceUuids?.any { normalizeUuid(it.uuid.toString()) == canonicalTarget } == true ||
            scanRecord?.serviceData?.keys?.any { normalizeUuid(it.uuid.toString()) == canonicalTarget } == true

        if (!hasServiceUuidTarget) {
            return null
        }

        val textBuilder = StringBuilder()
        scanRecord?.serviceData?.let { serviceDataMap ->
            for ((_, bytes) in serviceDataMap) {
                if (bytes != null && bytes.isNotEmpty()) {
                    val decodedText = decodeBytesToString(bytes)
                    if (decodedText.isNotBlank()) {
                        textBuilder.append(decodedText).append("\n")
                    }
                }
            }
        }

        scanRecord?.manufacturerSpecificData?.let { sparseArray ->
            for (i in 0 until sparseArray.size()) {
                val bytes = sparseArray.valueAt(i)
                if (bytes != null && bytes.isNotEmpty()) {
                    val decodedText = decodeBytesToString(bytes)
                    if (decodedText.isNotBlank()) {
                        textBuilder.append(decodedText).append("\n")
                    }
                }
            }
        }

        val messageId = if (textBuilder.isNotEmpty()) {
            textBuilder.toString().trim()
        } else {
            ""
        }

        // Query CAP message repository by received message ID
        val capMessage = CapRepository.getMessageById(messageId)
        val title = capMessage?.headline ?: "Unknown ID ($messageId)"
        val description = capMessage?.description ?: context.getString(R.string.unknown_beacon_message)

        val alertKey = messageId.ifBlank { "unknown" }
        val currentTime = System.currentTimeMillis() // Use reception time of the beacon
        val lastTime = SettingsPrefs.getAlertTimestamp(context, alertKey)
        val windowMs = duplicateWindowMins * 60 * 1000L
        val withinWindow = (currentTime - lastTime) < windowMs

        val isDuplicate = if (duplicateEnabled) {
            withinWindow
        } else {
            false
        }

        // If it passes RSSI and is not a duplicate, record the reception timestamp
        if (passesRssi && !isDuplicate) {
            SettingsPrefs.saveAlertTimestamp(context, alertKey, currentTime)
        }

        val isForeground = AppLifecycleTracker.isForeground
        val willShowPopup = passesRssi && !isDuplicate

        val allUuids = scanRecord?.serviceUuids?.joinToString(", ") { it.toString() } ?: "None"
        val serviceDataStr = scanRecord?.serviceData?.entries?.joinToString("; ") { 
            "${it.key}: ${bytesToHex(it.value)}" 
        } ?: "None"
        val mfgDataStr = buildString {
            val sparse = scanRecord?.manufacturerSpecificData
            if (sparse != null && sparse.size() > 0) {
                for (i in 0 until sparse.size()) {
                    append("CompanyID ${sparse.keyAt(i)}: ${bytesToHex(sparse.valueAt(i))}; ")
                }
            } else {
                append("None")
            }
        }
        val advDeviceName = scanRecord?.deviceName

        Log.i("StarIoTDebug", "==================================================")
        Log.i("StarIoTDebug", "AlertEvaluator Evaluation (Exact UUID Match: $canonicalTarget, Message ID: $messageId):")
        Log.i("StarIoTDebug", "  - MAC Address: $address")
        Log.i("StarIoTDebug", "  - Bluetooth Device Name (device.name): $btDeviceName")
        Log.i("StarIoTDebug", "  - Advertised Local Name (scanRecord.deviceName): $advDeviceName")
        Log.i("StarIoTDebug", "  - Resolved Title: $title")
        Log.i("StarIoTDebug", "  - Resolved Description: $description")
        Log.i("StarIoTDebug", "  - RSSI: $rssi dBm (Threshold: $rssiThreshold dBm, Passes RSSI: $passesRssi)")
        Log.i("StarIoTDebug", "  - All Service UUIDs: $allUuids")
        Log.i("StarIoTDebug", "  - Service Data: $serviceDataStr")
        Log.i("StarIoTDebug", "  - Manufacturer Specific Data: $mfgDataStr")
        Log.i("StarIoTDebug", "  - Alert Key: $alertKey")
        Log.i("StarIoTDebug", "  - Last Alert Timestamp: $lastTime (Current Time: $currentTime, Window: $duplicateWindowMins min, Within Window: $withinWindow)")
        Log.i("StarIoTDebug", "  - Is Duplicate: $isDuplicate")
        Log.i("StarIoTDebug", "  - App is Foreground: $isForeground")
        Log.i("StarIoTDebug", "  - Decision - Will Show Popup: $willShowPopup")
        Log.i("StarIoTDebug", "==================================================")

        return EvaluationResult(
            deviceName = title,
            advertisedText = description,
            rssi = rssi,
            address = address,
            passesRssi = passesRssi,
            hasServiceUuidTarget = hasServiceUuidTarget,
            isDuplicate = isDuplicate,
            isForeground = isForeground,
            willShowPopup = willShowPopup,
            btDeviceName = btDeviceName,
            advDeviceName = advDeviceName,
            serviceUuids = allUuids,
            serviceData = serviceDataStr,
            manufacturerData = mfgDataStr
        )
    }

    private fun normalizeUuid(uuidStr: String): String {
        val clean = uuidStr.trim().lowercase()
        return when (clean.length) {
            4 -> "0000$clean-0000-1000-8000-00805f9b34fb"
            8 -> "$clean-0000-1000-8000-00805f9b34fb"
            else -> clean
        }
    }

    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun decodeBytesToString(bytes: ByteArray): String {
        return try {
            String(bytes, Charsets.UTF_8).trim { it <= ' ' || !it.isDefined() }
        } catch (e: Exception) {
            bytes.map { Char(it.toInt() and 0xFF) }.joinToString("").trim()
        }
    }
}
