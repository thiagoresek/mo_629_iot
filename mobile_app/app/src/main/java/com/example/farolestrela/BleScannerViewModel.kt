package com.example.farolestrela

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class BleScannerViewModel : ViewModel() {

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _beacons = MutableStateFlow<List<BeaconItem>>(emptyList())
    val beacons: StateFlow<List<BeaconItem>> = _beacons.asStateFlow()

    private val _statusMessage = MutableStateFlow("Ready to scan")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _bluetoothEnabled = MutableStateFlow(true)
    val bluetoothEnabled: StateFlow<Boolean> = _bluetoothEnabled.asStateFlow()

    private val _newBeaconAlert = MutableStateFlow<BeaconItem?>(null)
    val newBeaconAlert: StateFlow<BeaconItem?> = _newBeaconAlert.asStateFlow()

    // Settings state
    private val _duplicateDetectionEnabled = MutableStateFlow(true)
    val duplicateDetectionEnabled: StateFlow<Boolean> = _duplicateDetectionEnabled.asStateFlow()

    private val _duplicateWindowMinutes = MutableStateFlow(10) // default 10 minutes
    val duplicateWindowMinutes: StateFlow<Int> = _duplicateWindowMinutes.asStateFlow()

    private val _rssiThreshold = MutableStateFlow(-80) // default -80 dBm (standard for proximity)
    val rssiThreshold: StateFlow<Int> = _rssiThreshold.asStateFlow()

    private val _alertSoundEnabled = MutableStateFlow(true) // default true (alarm sound on)
    val alertSoundEnabled: StateFlow<Boolean> = _alertSoundEnabled.asStateFlow()

    private val _rssiFilterEnabled = MutableStateFlow(true) // default true
    val rssiFilterEnabled: StateFlow<Boolean> = _rssiFilterEnabled.asStateFlow()

    private val _popupWarningsEnabled = MutableStateFlow(false) // default false
    val popupWarningsEnabled: StateFlow<Boolean> = _popupWarningsEnabled.asStateFlow()

    private val _debugNotificationsEnabled = MutableStateFlow(false) // default false
    val debugNotificationsEnabled: StateFlow<Boolean> = _debugNotificationsEnabled.asStateFlow()

    private val _serviceUuid = MutableStateFlow("1234") // default 1234
    val serviceUuid: StateFlow<String> = _serviceUuid.asStateFlow()

    private var appContext: Context? = null

    init {
        // Collect beacons from shared repository
        viewModelScope.launch {
            BeaconRepository.beacons.collect { list ->
                _beacons.value = list
            }
        }
        // Collect foreground alert events from shared repository
        viewModelScope.launch {
            BeaconRepository.foregroundAlert.collect { alert ->
                _newBeaconAlert.value = null
                _newBeaconAlert.value = alert
            }
        }
    }

    fun setDuplicateDetectionEnabled(enabled: Boolean) {
        _duplicateDetectionEnabled.value = enabled
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putBoolean(SettingsPrefs.KEY_DUPLICATE_ENABLED, enabled).apply() }
    }

    fun setDuplicateWindowMinutes(minutes: Int) {
        _duplicateWindowMinutes.value = minutes
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putInt(SettingsPrefs.KEY_DUPLICATE_WINDOW, minutes).apply() }
    }

    fun setRssiThreshold(threshold: Int) {
        _rssiThreshold.value = threshold
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putInt(SettingsPrefs.KEY_RSSI_THRESHOLD, threshold).apply() }
    }

    fun setAlertSoundEnabled(enabled: Boolean) {
        _alertSoundEnabled.value = enabled
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putBoolean(SettingsPrefs.KEY_SOUND_ENABLED, enabled).apply() }
    }

    fun setRssiFilterEnabled(enabled: Boolean) {
        _rssiFilterEnabled.value = enabled
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putBoolean(SettingsPrefs.KEY_RSSI_FILTER_ENABLED, enabled).apply() }
    }

    fun setPopupWarningsEnabled(enabled: Boolean) {
        _popupWarningsEnabled.value = enabled
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putBoolean(SettingsPrefs.KEY_POPUP_WARNINGS, enabled).apply() }
    }

    fun setDebugNotificationsEnabled(enabled: Boolean) {
        _debugNotificationsEnabled.value = enabled
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putBoolean(SettingsPrefs.KEY_DEBUG_NOTIFICATIONS_ENABLED, enabled).apply() }
    }

    fun setServiceUuid(uuid: String) {
        val trimmed = uuid.trim()
        _serviceUuid.value = if (trimmed.isEmpty()) "1234" else trimmed
        appContext?.let { SettingsPrefs.getPrefs(it).edit().putString(SettingsPrefs.KEY_SERVICE_UUID, _serviceUuid.value).apply() }
    }

    fun dismissAlert() {
        _newBeaconAlert.value = null
    }

    fun initBluetooth(context: Context) {
        appContext = context.applicationContext
        val prefs = SettingsPrefs.getPrefs(context)
        _duplicateDetectionEnabled.value = prefs.getBoolean(SettingsPrefs.KEY_DUPLICATE_ENABLED, true)
        _duplicateWindowMinutes.value = prefs.getInt(SettingsPrefs.KEY_DUPLICATE_WINDOW, 10)
        _rssiThreshold.value = prefs.getInt(SettingsPrefs.KEY_RSSI_THRESHOLD, -80)
        _alertSoundEnabled.value = prefs.getBoolean(SettingsPrefs.KEY_SOUND_ENABLED, true)
        _rssiFilterEnabled.value = prefs.getBoolean(SettingsPrefs.KEY_RSSI_FILTER_ENABLED, true)
        _popupWarningsEnabled.value = prefs.getBoolean(SettingsPrefs.KEY_POPUP_WARNINGS, false)
        _debugNotificationsEnabled.value = prefs.getBoolean(SettingsPrefs.KEY_DEBUG_NOTIFICATIONS_ENABLED, false)
        _serviceUuid.value = prefs.getString(SettingsPrefs.KEY_SERVICE_UUID, "1234") ?: "1234"

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter
        val isEnabled = bluetoothAdapter?.isEnabled == true
        _bluetoothEnabled.value = isEnabled
        if (!isEnabled) {
            _statusMessage.value = "Bluetooth is disabled. Please turn on Bluetooth."
        } else {
            startScanning()
        }
    }

    fun startScanning() {
        _isScanning.value = true
        _statusMessage.value = "Looking for health hazards..."
    }

    fun stopScanning() {
        _isScanning.value = false
        _statusMessage.value = "Scanning stopped. Found ${_beacons.value.size} health warnings."
    }

    fun clearBeacons() {
        BeaconRepository.clear()
        appContext?.let { 
            SettingsPrefs.clearLastShownAlert(it)
            SettingsPrefs.clearAlertTimestamps(it)
        }
        _statusMessage.value = "Health warnings list cleared."
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
    }
}
