package com.example.farolestrela

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object BeaconRepository {
    private val beaconMap = mutableMapOf<String, BeaconItem>()

    private val _beacons = MutableStateFlow<List<BeaconItem>>(emptyList())
    val beacons: StateFlow<List<BeaconItem>> = _beacons.asStateFlow()

    private val _foregroundAlert = MutableSharedFlow<BeaconItem>(extraBufferCapacity = 10)
    val foregroundAlert: SharedFlow<BeaconItem> = _foregroundAlert.asSharedFlow()

    fun addOrUpdateBeacon(beacon: BeaconItem) {
        val alertKey = "${beacon.name}::${beacon.advertisedText}"
        beaconMap[alertKey] = beacon
        _beacons.value = beaconMap.values.sortedByDescending { it.timestamp }
    }

    fun emitForegroundAlert(beacon: BeaconItem) {
        _foregroundAlert.tryEmit(beacon)
    }

    fun clear() {
        beaconMap.clear()
        _beacons.value = emptyList()
    }
}
