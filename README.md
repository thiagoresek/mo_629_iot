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
*(To be developed / TBD)*
