# MO629 IoT Project - Prototype
A BLE-Based IoT System for Proximity-Triggered Hazard Alerts

This repository contains the prototype implementation for the MO629 IoT Project, divided into two main components: the Android Mobile Application and the IoT + ThingsBoard backend infrastructure.

---

## 1. Mobile App
The source code for the Android application is located in the **`mobile_app`** folder.

### Key Features
- **BLE Scanning & Filtering**: Continuous low-latency background scanning with support for custom Service UUIDs and Service Data, alongside RSSI signal threshold filtering for proximity accuracy.
- **CAP XML Message Database**: An offline XML database bundled in assets (`cap_messages.xml`) that maps beacon message IDs (e.g. `0001`, `TEST`) to official alert titles and descriptions, with automatic hyperlink and phone number detection.
- **Unified Alert System**: Transparent emergency popups (`AlertActivity`) triggered consistently across both active use and idle background monitoring, complete with screen wake-up and lock-screen display capabilities.
- **Duplicate Detection & Controls**: Configurable time windows to prevent alert spam, a clear list history feature, and toggleable debug notifications for packet inspection.

### Tech Stack
- **Platform**: Android (Kotlin, Jetpack Compose, Material 3)
- **Background Service**: Foreground Service (`BleScanService`)
- **Data Parsing**: Android XmlPullParser (`CapRepository`)

### Getting Started (Mobile App)
1. Open the **`mobile_app`** folder in Android Studio.
2. Build and run the app on an Android device or emulator with Bluetooth enabled.

---

## 2. IoT + ThingsBoard
Three ESP32 Arduino sketches form the chain:

```
beacon <--BLE--> relay <--BLE--> gateway <--WiFi/MQTT--> ThingsBoard
```

- **`esp32_ble_alerta_cmd.ino` (Beacon)**: Advertises the alert value as Service Data (UUID `0x1234`) plus a BLE name (default `0001` / `IC`), and listens for signed update commands.
- **`esp32_relay_cmd.ino` (Relay)**: Re-broadcasts beacon alerts (tagged with `0x1235` to avoid loops) and forwards commands (`0x1236`) from the gateway to the beacon.
- **`esp32_gateway_cmd_debug.ino` (Gateway)**: Publishes detected alerts to ThingsBoard via MQTT and sends values edited in ThingsBoard (shared attributes `mensagemBeacon` and `nomeBeacon`) to the beacon over BLE.

### Key Features
- **Remote Beacon Update**: Commands are authenticated with a truncated HMAC-SHA256 tag and a sequence number to reject replays.
- **Delivery Confirmation**: The gateway confirms an update when it hears the beacon advertising the new value, retrying up to 5 times otherwise.
- **Serial Diagnostics**: The gateway logs its activity and accepts test commands (`status`, `reenviar`, `ajuda`) via the Serial Monitor.

### Tech Stack
- **Hardware**: ESP32
- **Firmware**: Arduino (ESP32 core: BLE, WiFi, Preferences, mbedtls)
- **Cloud**: ThingsBoard over MQTT (PubSubClient)

### Getting Started (IoT + ThingsBoard)
1. In the gateway sketch, fill in `WIFI_SSID`, `WIFI_SENHA` and `THINGSBOARD_TOKEN`, and make sure `CHAVE_COMANDO` is identical in all three sketches.
2. Install the **PubSubClient** library and, for the gateway, select **Tools > Partition Scheme > "Huge APP (3MB No OTA)"**.
3. Flash each sketch to its ESP32 and open the Serial Monitor at **115200 baud**.
