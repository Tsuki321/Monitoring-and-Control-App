# HydroSense — Water Monitoring & Control

[![Android CI](https://github.com/Tsuki321/Monitoring-and-Control-App/actions/workflows/android_ci.yml/badge.svg)](https://github.com/Tsuki321/Monitoring-and-Control-App/actions/workflows/android_ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

HydroSense is Group 1's Android companion app for an ESP32 water filtration system. It reads live sensor data from Firebase, shows tank and pump status, estimates filter wear, and provides manual and automatic pump controls.

## Current features

| Area | What the app provides |
|---|---|
| Dashboard | Animated tank level, water-quality readings, pump status and filter overview |
| Monitoring | pH, TDS, turbidity, tank distance/percentage, tank warning and leak status |
| Control | Auto/manual mode and Pump A/Pump B commands, with actual relay feedback; manual pump switches are disabled during a detected leak or automatic mode |
| Filter health | Five-stage filter estimates based on measured water-quality readings and cumulative Pump B runtime; replacement tracking |
| Background alerts | Full-tank and leak notifications with vibration while the app is closed or in the background |
| Accounts | Email registration/login and verification, Google/Facebook sign-in, saved accounts, logout and account switching |
| Settings | English/Filipino, theme selection, tank/leak alerts, notification settings and tutorial replay |
| Help and legal | Guided first-use tour, About, Privacy Policy and Terms and Conditions |

Sensor range labels and filter-life estimates do not establish that water is safe to drink.

## Tank and leak notifications

Alerts are **on by default**. After signing in and accepting any required terms, the first opening of each installed app version shows the alert setup again, including for existing users after an update.

- **Android 13 and later:** enabling alerts requests the notification runtime permission. If permission is already granted, Android does not ask again. If permission or a notification channel is blocked, the app offers Android notification settings.
- **Android 8–12:** there is no notification runtime prompt; blocked notifications can be enabled in Android settings.
- Choosing **Not now** leaves the app's default alert setting on. Delivery still requires Android notification permission. Turn monitoring off explicitly in **Settings → Tank and leak alerts**.
- Full-tank and leak alerts use separate high-importance notification channels with a vibration pattern. Sound and vibration follow the phone's channel settings and Do Not Disturb configuration.

The foreground service keeps listening after Home, Back or swiping the app out of Recents. Android displays a quiet, ongoing **Tank and leak monitoring active** notification while it runs. Only the warning notifications are suppressed while the app is visible.

A full-tank alert is triggered by `tankWarning = 3` or `tankLevel = 100`. It alerts once per fill cycle and rearms after a confirmed non-full reading at or below 95% (or a cleared warning when percentage is unavailable). A leak alert is triggered by `rainDetected = true` and rearms after a confirmed dry reading. Both events keep their handled state across service restarts, independently for each account. An event observed while using the app does not produce a delayed alert just because the app is then closed.

Monitoring stops on logout or when alerts are switched off. Previously enabled monitoring can resume after a reboot or app update. Android **Force stop** stops it until the app is opened again. A working internet connection and current ESP32 telemetry are needed; device battery restrictions and Doze can delay network updates. There is no server push fallback.

The service uses Android's `specialUse` foreground-service type, with its monitoring purpose declared in the manifest. A Google Play release requires the corresponding foreground-service declaration/review. See [Android foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [notification permissions](https://developer.android.com/develop/ui/views/notifications/notification-permission).

## Live data and filter estimates

The app uses Firebase Authentication and Firebase Realtime Database. Production screens receive measured readings from `FirebaseRealtimeSensorRepository`; a separate tank/leak listener allows notifications to work even when water-quality probes have incomplete readings.

| Firebase path | Purpose |
|---|---|
| `/sensors` | Water quality, tank measurements, leak state and cumulative pump runtime |
| `/sensors/tankLevel`, `/sensors/tankWarning` | Tank percentage and warning code: 0 normal, 1 at 80%, 2 at 90%, 3 full |
| `/sensors/rainDetected` | Floor/tray moisture sensor; the app presents this as a **leak**, preserving the firmware key |
| `/sensors/runtimeB` | ESP32 cumulative seconds of five-stage Pump B operation |
| `/control/pumpA`, `/control/pumpB`, `/control/auto` | Requested pump states and mode |
| `/status` | Actual relay states reported by the ESP32 |

Filter life remains an estimate from a model trained on generated data. Its live inputs use measured sensor values and the ESP32's cumulative Pump B counter. Missing or unusable readings pause wear updates; runtime is not simulated or accelerated. Counter deltas include operation while the app was closed. Pump A is intake-only and does not drive five-stage filter wear. Legacy history without a measured counter is cleared on upgrade; measured history is preserved.

The [application and system flowcharts](docs/flowcharts/README.md) include printable PDFs, images and editable diagrams.net files.

## Architecture and project layout

The app uses Kotlin, MVVM, repositories, Coroutines/Flow, XML layouts and ViewBinding. Navigation is hosted in `MainActivity` after the animated `SplashActivity`. A foreground Android service owns background alert monitoring; process lifecycle state controls whether warning notifications may be posted.

```text
Monitoring, Dashboard, and Control/WaterMonitor/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/watermonitor/app/
│   │   ├── HydroSenseApp.kt
│   │   ├── MainActivity.kt
│   │   ├── SplashActivity.kt
│   │   ├── data/
│   │   │   ├── ml/             # Filter-life estimation
│   │   │   ├── model/          # Sensor, pump, account and filter models
│   │   │   └── repository/     # Firebase telemetry and filter health
│   │   ├── notifications/     # Service, alert policies, channels and restart receiver
│   │   ├── ui/                # Auth, dashboard, monitoring, control, filters,
│   │   │                      # settings, tutorial, legal, About and custom views
│   │   └── utils/             # Preferences, locale, theme and account helpers
│   └── res/                   # Layouts, graphics and English/Filipino strings
├── app/src/test/               # JUnit and Robolectric tests
└── gradle/libs.versions.toml   # Dependency versions
```

| Component | Repository configuration |
|---|---|
| Android | Minimum API 26 (Android 8); compile/target API 35 |
| Kotlin / Android Gradle Plugin | 2.1.0 / 8.7.3 |
| UI | AndroidX, Material Design 3, Navigation, ViewBinding |
| State and lifecycle | Coroutines 1.9.0, Flow/StateFlow, AndroidX Lifecycle |
| Backend | Firebase Auth, Realtime Database and Firestore dependencies |
| Tests | JUnit 4.13.2, Robolectric 4.14.1 |
| CI Java | JDK 17 |

## Install and develop

1. Open [Android CI](https://github.com/Tsuki321/Monitoring-and-Control-App/actions/workflows/android_ci.yml) and select a successful run for the revision you want.
2. Download and extract `WaterMonitor-debug-apk` or `WaterMonitor-release-apk`, then install the APK on an Android 8+ device.
3. Sign in, complete any required consent, and allow notifications when the alert setup appears.
4. Keep the ESP32 and phone online. Use **Settings → Notification settings** to inspect the two alert channels and vibration options.

For source changes, clone the repository and open `Monitoring, Dashboard, and Control/WaterMonitor/`. **Builds and tests run in GitHub Actions only; do not compile or run Gradle tests locally.**

The [Android CI workflow](.github/workflows/android_ci.yml) runs on pushes to `main`/`master`, pull requests targeting those branches, and manual dispatches. It runs unit tests, builds signed debug and release APKs, and retains the APK artifacts for 14 days. Failed test reports are uploaded when available.

Firebase configuration must match package `com.watermonitor.app`, the configured Realtime Database URL, enabled authentication providers and provider signing certificates. The checked-in app configuration is project-specific; configure your own backend for a separate deployment.

CI signing uses the GitHub secrets `KEYSTORE_BASE64`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`. The workflow decodes the keystore and provides `SIGNING_STORE_FILE` to Gradle. Keep signing material, Wi-Fi passwords and ESP32 authentication credentials out of version control.

## Verify background alerts on a device

1. Install an update over the previous version. Open it and confirm the alert setup appears once for that version, the setting defaults on, and notification permission is requested when needed.
2. With the app visible, trigger a full tank and a leak using test telemetry. Confirm no warning notification or notification vibration occurs.
3. Clear both conditions, put the app in the background, and trigger each again. Confirm separate notifications and vibration; repeated identical readings must not repeat alerts.
4. Repeat with the app swiped out of Recents and the screen locked. Tap an alert to reopen HydroSense.
5. Deny permission, return from Android notification settings, disable/re-enable monitoring, sign out, restart the phone, and check duplicate suppression and restart behavior.

## License and project team

HydroSense is developed by **Group 1** for its water filtration thesis project. Contact: ciriloenriquez123@gmail.com.

The software is licensed under the [MIT License](LICENSE). The in-app Terms and Conditions govern use of the project-operated service and connected equipment without restricting rights granted by that license.
