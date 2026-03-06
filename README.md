# 💧 WaterMonitor — Smart Water Monitoring & Control

[![Android CI](https://github.com/Tsuki321/Monitoring-and-Control-App/actions/workflows/android_ci.yml/badge.svg)](https://github.com/Tsuki321/Monitoring-and-Control-App/actions/workflows/android_ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platform](https://img.shields.io/badge/Platform-Android-brightgreen)
![Min SDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-orange)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)

A native **Android application** for real-time water quality monitoring and system control. WaterMonitor provides an ocean-themed, animated UI to visualise sensor data (pH, TDS, Turbidity), track tank levels, and toggle pumps and valves — all powered by a clean MVVM architecture.

---

## ✨ Features

| Feature | Description |
|---|---|
| 📊 **Dashboard** | At-a-glance overview: animated water tank, pump status, and sensor health |
| 🔬 **Real-Time Monitoring** | Live pH, TDS, and Turbidity readings with animated count-up transitions |
| 🎛️ **System Control** | Toggle Pump A, Pump B, Main Valve, and Bypass Valve with instant feedback |
| 🌊 **Ocean Theme** | Multi-layer animated wave background and glassmorphic card design |
| ⚡ **Smooth Animations** | Splash screen, staggered card entrances, value count-ups, and pulse effects |
| 🕐 **Live Clock** | Persistent top bar showing date and time, updated every 30 seconds |

---

## 📸 Screenshots

<p align="center">
  <img src="Monitoring%20Concept%20Art.png" alt="App Concept Art" width="600"/>
</p>

---

## 🏗️ Architecture

WaterMonitor follows the **MVVM (Model-View-ViewModel)** pattern with a unidirectional data flow:

```
UI Layer (Fragment / Activity)
        │  observes StateFlow
        ▼
  ViewModel (business logic + UI state)
        │  collects Flow
        ▼
 Repository (MockSensorRepository)
        │  emits simulated sensor data via Kotlin Flow
        ▼
   Data Models (SensorData, TankStatus, PumpState, …)
```

- **Activities:** `SplashActivity` → `MainActivity`
- **Fragments:** `DashboardFragment`, `MonitoringFragment`, `ControlFragment`
- **Custom Views:** `WaterTankView` (animated fill), `OceanWaveView` (wave background)
- **Navigation:** AndroidX Navigation Component with a single `NavHost`

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.1.0 |
| UI | AndroidX, Material Design 3, ConstraintLayout |
| Navigation | AndroidX Navigation Component 2.8.5 |
| State | Kotlin Coroutines 1.9.0, StateFlow / Flow |
| Architecture | MVVM + Repository pattern |
| View binding | ViewBinding |
| Build system | Gradle 8 (Kotlin DSL) |
| Min / Target SDK | 26 / 35 (Android 8.0 – Android 15) |

---

## 📁 Project Structure

```
Monitoring, Dashboard, and Control/
└── WaterMonitor/
    └── app/src/main/
        ├── java/com/watermonitor/app/
        │   ├── MainActivity.kt           # Host activity with nav & top bar
        │   ├── SplashActivity.kt         # Animated splash screen
        │   ├── data/
        │   │   ├── model/                # SensorData, TankStatus, PumpState, …
        │   │   └── repository/           # MockSensorRepository (Flow-based)
        │   └── ui/
        │       ├── dashboard/            # DashboardFragment + ViewModel
        │       ├── monitoring/           # MonitoringFragment + ViewModel
        │       ├── control/              # ControlFragment + ViewModel
        │       └── views/               # WaterTankView, OceanWaveView, AnimationUtils
        └── res/
            ├── layout/                   # XML layouts
            ├── drawable/                 # Icons, animated vector drawables
            ├── navigation/               # nav_graph.xml
            └── values/                   # Strings, colors, dimensions, themes
```

---

## 🚀 Getting Started

### Prerequisites

| Requirement | Version |
|---|---|
| Android Studio | Hedgehog (2023.1.1) or newer |
| JDK | 17 or newer |
| Android SDK | API 26 (minimum), API 35 (target) |
| Kotlin | 2.1.0 |

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/Tsuki321/Monitoring-and-Control-App.git
   cd Monitoring-and-Control-App
   ```

2. **Open in Android Studio**
   - Open Android Studio → **File → Open**
   - Navigate to `Monitoring, Dashboard, and Control/WaterMonitor` and click **OK**
   - Wait for Gradle sync to complete

3. **Run the app**
   - Connect an Android device (API 26+) or start an emulator
   - Click **▶ Run** (or press `Shift+F10`)

### Building from the command line

```bash
cd "Monitoring, Dashboard, and Control/WaterMonitor"

# Debug build
./gradlew assembleDebug

# Release build (requires signing configuration — see below)
./gradlew assembleRelease
```

Built APKs are placed in `app/build/outputs/apk/`.

---

## 🔐 Release Signing

The release build reads signing credentials from environment variables:

| Environment Variable | Description |
|---|---|
| `SIGNING_STORE_FILE` | Path to the `.jks` keystore file |
| `SIGNING_STORE_PASSWORD` | Keystore password |
| `SIGNING_KEY_ALIAS` | Key alias |
| `SIGNING_KEY_PASSWORD` | Key password |

For CI/CD, these are stored as **GitHub Actions secrets** (see `.github/workflows/android_ci.yml`).

---

## ⚙️ CI/CD

GitHub Actions automatically builds and signs the app on every push to `main`/`master` and on every pull request.

**Workflow:** `.github/workflows/android_ci.yml`

**Steps:**
1. Check out code
2. Set up JDK 17
3. Cache Gradle packages
4. Decode keystore from `KEYSTORE_BASE64` secret
5. Build **debug** APK (`assembleDebug`)
6. Build **release** APK (`assembleRelease`)
7. Upload both APKs as GitHub Actions artifacts (14-day retention)

---

## 📡 Sensor Data

Currently the app uses **MockSensorRepository** which simulates realistic sensor readings using sine-wave oscillation with random jitter, updated every 3 seconds:

| Sensor | Range | Unit |
|---|---|---|
| pH | 6.8 – 7.8 | — |
| TDS (Total Dissolved Solids) | 120 – 200 | ppm |
| Turbidity | 0.5 – 3.5 | NTU |
| Tank Fill Level | 10 – 100 | % |

The repository layer is designed to be swapped out for a real backend (REST API, MQTT broker, or Bluetooth LE) with minimal changes to the ViewModel layer.

---

## 🤝 Contributing

Contributions are welcome! Please follow these steps:

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature-name`
3. Commit your changes: `git commit -m "Add your feature"`
4. Push to your branch: `git push origin feature/your-feature-name`
5. Open a Pull Request

Please ensure your code follows the existing Kotlin style and that all existing tests pass.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

## 👤 Author

**Tsuki321**  
GitHub: [@Tsuki321](https://github.com/Tsuki321)
