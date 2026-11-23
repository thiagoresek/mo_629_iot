package com.example.farolestrela

data class BeaconItem(
    val address: String,
    val name: String?,
    val rssi: Int,
    val advertisedText: String,
    val timestamp: Long,
    val btDeviceName: String? = null,
    val advDeviceName: String? = null,
    val serviceUuids: String = "None",
    val serviceData: String = "None",
    val manufacturerData: String = "None"
)
