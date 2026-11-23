# MO629 IoT Project - Prototype
A BLE-Based IoT System for Proximity-Triggered Hazard Alerts

Star IoT is an Android application designed to scan for Bluetooth Low Energy (BLE) hazard beacons. When a beacon is detected, the app reads its broadcasted message ID, looks up the corresponding alert in an offline Common Alerting Protocol (CAP) XML database, and displays an emergency warning popup with alarm sound support, both while the app is active and when running in the background.

## Key Features

- **BLE Scanning & Filtering**: Continuous low-latency background scanning with support for custom Service UUIDs and Service Data, alongside RSSI signal threshold filtering for proximity accuracy.
- **CAP XML Message Database**: An offline XML database bundled in assets (`cap_messages.xml`) that maps beacon message IDs (e.g. `0001`, `TEST`) to official alert titles and descriptions, with automatic hyperlink and phone number detection.
- **Unified Alert System**: Transparent emergency popups (`AlertActivity`) triggered consistently across both active use and idle background monitoring, complete with screen wake-up and lock-screen display capabilities.
- **Duplicate Detection & Controls**: Configurable time windows to prevent alert spam, a clear list history feature, and toggleable debug notifications for packet inspection.

## Tech Stack

- **Platform**: Android (Kotlin, Jetpack Compose, Material 3)
- **Background Service**: Foreground Service (`BleScanService`)
- **Data Parsing**: Android XmlPullParser (`CapRepository`)

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/YOUR_USERNAME/YOUR_REPOSITORY_NAME.git
   ```
2. Open the project in Android Studio.
3. Build and run the app on an Android device or emulator with Bluetooth enabled.
