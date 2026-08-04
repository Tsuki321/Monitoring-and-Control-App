# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

**HydroSense** (package: `com.watermonitor.app`) — A native Android app for real-time water quality monitoring and system control with an ocean-themed animated UI. Built with Kotlin, MVVM architecture, and Material Design 3.

**Critical:** The Android project lives in a nested directory with spaces: `Monitoring, Dashboard, and Control/WaterMonitor/`. Always quote paths in shell commands.

## Development Workflow

⚠️ **IMPORTANT: No local builds or testing.** All compilation and testing must be done through GitHub Actions CI/CD pipeline.

- **Do NOT run:** `./gradlew build`, `./gradlew assembleDebug`, `./gradlew test`, or any other Gradle build commands locally
- **Instead:** Commit and push changes to trigger the GitHub Actions workflow
- **Rationale:** Ensures consistent build environment, proper signing, and validates changes in the same environment as production builds
- **Exception:** Code inspection, refactoring, and file editing can be done locally without building
- **Secrets:** Never commit credentials. `.gitignore` excludes `UID.txt` (ESP32 Firebase Auth credentials), `arduino_secrets.h`, `Sketch Arduino/` (ESP32 firmware with Wi-Fi & Firebase credentials), `*.apk`/`*.aab` (build outputs), and `*.keystore`/`*.jks` (signing keys).

## Build & Testing Commands

⚠️ **These commands are for reference only. DO NOT execute them locally. All builds must go through GitHub Actions.**

### CI/CD Build Process

When you push to `main`/`master` or open a PR, GitHub Actions automatically:

1. Builds debug APK (`assembleDebug`)
2. Builds signed release APK (`assembleRelease`)
3. Uploads both APKs as downloadable artifacts (14-day retention)

Download APKs from the Actions tab: `https://github.com/Tsuki321/Monitoring-and-Control-App/actions`

### Gradle Commands (Reference Only)

If local builds were allowed, these would be the commands:

```bash
cd "Monitoring, Dashboard, and Control/WaterMonitor"

# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Install debug APK to device
./gradlew installDebug

# Run tests
./gradlew test

# Clean build artifacts
./gradlew clean
```

### Release Signing

Release builds read signing credentials from environment variables (set in CI or locally):

- `SIGNING_STORE_FILE` — Path to `.jks` keystore
- `SIGNING_STORE_PASSWORD` — Keystore password
- `SIGNING_KEY_ALIAS` — Key alias
- `SIGNING_KEY_PASSWORD` — Key password

Without these, release builds will be unsigned. Debug builds use the default Android debug keystore.

## Architecture

### MVVM + Repository Pattern

```
Fragment/Activity (UI)
    │ observes StateFlow
    ▼
ViewModel (business logic)
    │ collects Flow
    ▼
Repository (FirebaseRealtimeSensorRepository | MockSensorRepository)
    │ emits data via Kotlin Flow
    ▼
Data Models (SensorData, TankStatus, PumpState, etc.)
```

**Key architectural components:**

1. **FirebaseRealtimeSensorRepository** (`data/repository/`) — Listens to Firebase Realtime Database via `ValueEventListener` wrapped in `callbackFlow`. Exposes `sensorDataFlow` (reads `/sensors` → `SensorData`) and `pumpControlFlow` (reads `/status` + `/control/auto` → `PumpControlState`). Writers: `setPumpA()`, `setPumpB()`, `setAutoMode()` push commands to `/control`. Requires Firebase Auth (`auth != null` per RTDB rules). Connects to the regional database URL (`asia-southeast1.firebasedatabase.app`) and waits for a signed-in user before attaching listeners; logs under tag `HydroSenseRTDB`.

2. **MockSensorRepository** (`data/repository/`) — Singleton object providing simulated data for **Dashboard** and **Control**. Emits pH/TDS/turbidity on a timer only if wired to Monitoring (currently not). Tank fill level updates independently. Pump/valve state is managed via `MutableStateFlow`. `setPumpA()` / `setPumpB()` sync actual relay states from RTDB into the mock so the speed/voltage simulation reflects hardware reality.

3. **Custom Views** (`ui/views/`)
   - `WaterTankView` — Animated water tank with fill level, surface wave animation (2.5s loop), and smooth fill transitions
   - `OceanWaveView` — Multi-layer parallax wave background using `Choreographer` for frame-perfect animation
   - Both use delta-time calculations to avoid Float precision loss (see "Animation System" below)

4. **Navigation** — Single-activity architecture with AndroidX Navigation Component. Bottom nav visible on main screens (Dashboard, Monitoring, Control), hidden on Settings/About/Auth pages. Top bar dynamically switches between settings gear and back arrow.

### Data Flow

- **Sensor readings (Monitoring):** `FirebaseRealtimeSensorRepository.sensorDataFlow` → `MonitoringViewModel` → `StateFlow` → `MonitoringFragment` (animated count-ups + tank/leak card)
- **Tank + leak status:** RTDB `/sensors` fields `tankLevel`, `tankDistanceMm`, `tankWarning`, `rainDetected` → `SensorData` → Monitoring tank card; `rainDetected` also feeds the Dashboard system status card. App maps firmware key `rainDetected` → domain field `leakDetected` (moisture/leak sensor, not weather). Tank level is shown on Monitoring only — the Dashboard Pump Status card lists leak + Pump A/B.
- **Pump control (bidirectional):**
  - App reads actual relay states: `FirebaseRealtimeSensorRepository.pumpControlFlow` (listens to `/status`) → `ControlViewModel.pumpControlState` → `ControlFragment` (switches reflect actual pump on/off)
  - App sends commands: `ControlFragment` → `ControlViewModel.togglePumpA/B()` → `FirebaseRealtimeSensorRepository.setPumpA/B()` (writes to `/control/pumpA` or `/control/pumpB`)
  - Mode toggle: `ControlFragment` → `ControlViewModel.toggleAutoMode()` → `FirebaseRealtimeSensorRepository.setAutoMode()` (writes to `/control/auto`)
  - ESP32 reads `/control` every 500ms, drives relays, writes actual states back to `/status` — creating a live feedback loop
  - Safety: tank full (100%) and leak (`rainDetected=1`) force-stop both pumps on the ESP32; float switch (Pump A only) is firmware-local and not published
  - Speed/voltage simulation: `MockSensorRepository.setPumpA/B()` syncs actual states → mock → `pumpState` flow → UI speed/voltage labels

### Realtime Database schema (current)

Project: `database-for-hydrosense` (see `app/google-services.json`).
Database URL: `https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app`

```json
{
  "sensors": {
    "ph": 7.2,
    "tds": 450,
    "turbidity": 12.5,
    "tankDistanceMm": 500,
    "tankLevel": 48.3,
    "tankWarning": 0,
    "rainDetected": 0
  },
  "status": {
    "pumpA": 0,
    "pumpB": 0
  },
  "control": {
    "pumpA": 0,
    "pumpB": 0,
    "auto": 1
  }
}
```

**`/sensors` field notes (firmware V14):**

| Field | Type | Meaning |
|-------|------|---------|
| `ph` / `tds` / `turbidity` | number | Water quality readings |
| `tankDistanceMm` | int | VL53L1X ToF distance mm; `-1` = ToF offline (float-switch fallback) |
| `tankLevel` | float | Fill % (0–100) from ToF; float-switch estimate if ToF offline |
| `tankWarning` | int | `0` normal, `1` ≥80%, `2` ≥90%, `3` ≥100% full |
| `rainDetected` | 0/1 | **Leak / moisture sensor** (not weather). `1` = wet → ESP32 stops both pumps |

**Path ownership (prevents write conflicts):**

| Path | Writer | Reader | Purpose |
|------|--------|--------|---------|
| `/sensors` | ESP32 | App | pH, TDS, turbidity, tank level, leak flag |
| `/status` | ESP32 | App | Actual relay states (`pumpA`, `pumpB` as 0/1) |
| `/control` | App | ESP32 | Commands + mode (`pumpA`, `pumpB` as 0/1; `auto` as 0/1) |

`/control/auto`: `1` = AUTO mode (ESP32 water-level automation drives both pumps + safety failsafes); `0` = MANUAL mode (each pump independently follows `/control/pumpA` and `/control/pumpB`). Defaults to `1` (AUTO) when missing/null.

**Security rules (current):** `/sensors`, `/status`, and `/control` read and write only when `auth != null`. The Android app reads/writes after user sign-in. ESP32 uses a dedicated auth user (see phase 2a).

### Roadmap: ESP32 writes → Android reads

**Done (phase 1 — Android read path):**
- `firebase-database` dependency on Firebase BOM
- `FirebaseRealtimeSensorRepository` listening on `getReference("sensors")`
- `MonitoringViewModel` switched from mock to RTDB
- Manual verification: edit values in Firebase Console; Monitoring UI updates live

**Done (phase 2a — ESP32 auth setup):**
- Chosen approach: **Dedicated Firebase Auth user on device** (email/password)
- Device user created in Firebase Auth; credentials (email + UID) stored locally in `UID.txt` (gitignored — never commit)
- Existing `/sensors` rules (`auth != null`) already permit this user to write

**Done (phase 2b — ESP32 publish):**
- **Firmware** — `Sketch Arduino/UPDATED_CODE_V14.ino` reads pH, TDS, turbidity on 1s intervals and publishes every 5s. Uses non-blocking `millis()` timing (no `delay()` in loop).
- **Firebase write auth** — Dedicated device user via `UserAuth` (email/password, see phase 2a). Alternatives considered but rejected:
  - **Custom token / service account** (server or Cloud Function mints short-lived tokens for the device — more secure, more setup)
  - **Rules change for device path** — e.g. allow write to `/sensors` only when `auth != null` for app and a separate locked path for devices (avoid wide open `.write: true`)
- **Payload contract** — Single atomic `update<object_t>()` PATCH to `/sensors` with JSON `{"ph":X.XX,"tds":XXX,"turbidity":X.X,"tankDistanceMm":N,"tankLevel":X.X,"tankWarning":N,"rainDetected":0|1}`. One network round-trip per cycle.
- **Arduino stack** — `FirebaseClient` library by mobizt (NOT the deprecated `Firebase-ESP-Client`). Install via Arduino Library Manager (search "FirebaseClient"). Auth token auto-refreshes (60-min lifetime handled by `app.loop()`).
- **Wi-Fi** — `WiFi.setAutoReconnect(true)` + `WiFi.persistent(true)` for drop recovery. TLS via `WiFiClientSecure::setInsecure()` (prototype; use `setCACert()` for production).
- **Serial diagnostics** — 115200 baud. Prints Wi-Fi IP/RSSI, auth progress (`[FB]`), `[OK] Firebase auth complete`, each publish payload + target URL, `[OK] Publish successful` or `[FAIL]` with error code.

**Done (phase 2c — bidirectional pump control):**
- **Firmware** — ESP32 polls `/control` every 500ms for `pumpA`, `pumpB`, and `auto` commands. In AUTO mode (`auto=1`, default), both pumps follow tank fill % with float-switch gate on Pump A. In MANUAL mode (`auto=0`), each pump follows `/control/pumpA` or `/control/pumpB` (Pump A still gated by float switch). Safety overrides: tank 100% full and leak sensor wet (`rainDetected`) force-stop both pumps. Actual relay states are written to `/status` as `{"pumpA":0|1,"pumpB":0|1}` whenever either changes.
- **App** — `FirebaseRealtimeSensorRepository.pumpControlFlow` combines `/status` (actual states) + `/control/auto` (mode) into `PumpControlState`. `ControlViewModel` exposes this as a `StateFlow`; `ControlFragment` switches reflect actual relay states. Toggling a switch writes a command to `/control/pumpA` or `/control/pumpB`. An Auto/Manual mode switch writes to `/control/auto`; pump switches are disabled in AUTO mode and when a leak is active. `MockSensorRepository.setPumpA/B()` syncs real states → mock so speed/voltage simulation reflects hardware. Dashboard also syncs via `DashboardViewModel` init.
- **Feedback loop** — App writes command → ESP32 reads (≤500ms) → ESP32 drives relay → ESP32 writes `/status` → App listener updates UI. Total round-trip: ~1–2 seconds.

**Done (phase 2d — tank level + leak sensor app integration):**
- **Firmware V14** — VL53L1X ToF tank level + warnings; moisture/leak sensor on GPIO33 published as `rainDetected`; float switch on GPIO25 (Pump A safety, not published).
- **App** — Parses tank + `rainDetected` → `SensorData.leakDetected`. Monitoring shows tank fill/distance/warnings + leak status. Dashboard system card shows leak + Pump A/B (no tank row). Control shows leak banner and disables pump switches while wet.

**Pending (end-to-end test on hardware):**
- ESP32 publishing → Monitoring screen matches hardware readings without Console edits. Verify via Serial Monitor (`[OK] Publish successful`) + app logcat (`HydroSenseRTDB` tag, `onDataChange`).
- Pump control round-trip: Toggle a pump in the app → Serial Monitor shows `[CTRL]` → `/status` updates → app switch reflects actual state. Toggle Auto/Manual and verify pump switches enable/disable.
- Leak: wet the moisture sensor → Serial shows rain/leak override → `/sensors/rainDetected=1` → app leak UI + pumps stop.

**Later (phase 3 — app parity):**
- Drive Dashboard `SensorStatus` online/offline from RTDB presence or `updatedAt` threshold
- Optional publish float-switch state for UI visibility
- Replace remaining `MockSensorRepository` usage (valve toggles, simulated speed/voltage)
- Optional `SensorRepository` interface + DI for mock vs production builds

### Swapping other backends

Monitoring already uses RTDB. For additional sources:

1. Add a repository exposing `Flow<SensorData>` (or domain-specific flows)
2. Point the relevant ViewModel at the new repository
3. Keep Fragment/UI unchanged where possible

Other integration options: REST (Retrofit), MQTT (Paho), BLE characteristics parsed into `SensorData`.

## Animation System

### Critical: Float Precision Issue

**Never use `System.currentTimeMillis()` directly in animation calculations.** Millisecond timestamps overflow Float precision, causing frozen animations. This was fixed in commit `6124c22`.

**Correct pattern (delta-time based):**
```kotlin
private var lastFrameTimeNanos = 0L

override fun doFrame(frameTimeNanos: Long) {
    val deltaSeconds = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0
    lastFrameTimeNanos = frameTimeNanos
    
    phase = (phase + deltaSeconds * speed).toFloat() % TWO_PI
}
```

See `OceanWaveView.kt` for reference implementation using `Choreographer.FrameCallback`.

### Animation Utilities

`AnimationUtils` provides reusable animations:
- `animateTextCount()` — Count-up transitions for sensor values with configurable decimal places
- `animateCardEntrance()` — Staggered slide-up + fade-in for lists of cards
- `pulseView()` — Single scale pulse (e.g., on button press)
- `animatePressDown/Up()` — Press feedback for interactive cards
- `fadeIn()` — Simple opacity transition

All use `DecelerateInterpolator` or `OvershootInterpolator` for natural motion.

## Firebase Integration

- **Auth:** `LoginFragment`, `RegisterFragment` — email/password, Google, Facebook. Required for RTDB reads under current rules.
- **Realtime Database:** Live sensor path `/sensors` → `FirebaseRealtimeSensorRepository` → Monitoring UI. Listener removed on flow cancel via `awaitClose`. Database URL is regional (`asia-southeast1`) and set explicitly in the repository (it is not present in `google-services.json`). Listener only attaches after a Firebase user is signed in; logcat tag `HydroSenseRTDB`.
- **Firestore:** On classpath; not used in sensor flow yet.
- **google-services.json:** Required in `app/`; must match package `com.watermonitor.app` and project `database-for-hydrosense`.

If Monitoring shows defaults forever: confirm user is signed in, rules allow `/sensors` for `auth != null`, data exists under `sensors` (not only at DB root), and the regional database URL in `FirebaseRealtimeSensorRepository.DATABASE_URL` matches Firebase Console.

## Testing

⚠️ **No local testing.** All tests must run through GitHub Actions CI/CD.

Currently no unit/instrumentation tests are included. When tests are added, they will run automatically in the CI pipeline.

**If tests were to be added locally (not recommended):**

Test source directories:
- `app/src/test/java/` — JUnit unit tests
- `app/src/androidTest/java/` — Instrumented tests

Commands that would run tests (reference only):
```bash
# Unit tests
./gradlew test

# Instrumented tests on connected device
./gradlew connectedAndroidTest
```

## CI/CD

GitHub Actions workflow (`.github/workflows/android_ci.yml`) runs on every push/PR to `main`/`master`:

1. Builds debug APK
2. Builds signed release APK (using secrets)
3. Uploads both APKs as artifacts (14-day retention)

**Secrets required in GitHub:**
- `KEYSTORE_BASE64` — Base64-encoded keystore file
- `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`

## Dependencies

Managed via `gradle/libs.versions.toml` (version catalog):

- **Kotlin:** 2.1.0
- **AGP:** 8.7.3
- **Navigation:** 2.8.5
- **Lifecycle/ViewModel:** 2.8.7
- **Coroutines:** 1.9.0
- **Firebase BOM:** 34.0.0 (includes `firebase-auth`, `firebase-database`, `firebase-firestore`)

To update dependencies, edit `libs.versions.toml` and push for CI validation (no local Gradle sync required for agents).

## Common Issues

### Animations not running
- Check that `isInEditMode` guards are in place (prevents crashes in layout preview)
- Ensure `onAttachedToWindow` starts animators and `onDetachedFromWindow` stops them
- Verify delta-time calculation doesn't use `currentTimeMillis()` (see Animation System above)

### Build fails with signing error
- Release builds require signing env vars. Use `assembleDebug` for local development.
- Check that `SIGNING_STORE_FILE` path is correct (relative or absolute)
- **Remember:** Do not build locally — verify build success in GitHub Actions instead

### Navigation crash
- Ensure `nav_graph.xml` has correct fragment names matching the Kotlin class paths
- Check that `NavHostFragment` ID in `activity_main.xml` matches the ID referenced in `MainActivity`

### "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"
- Run `gradle wrapper` to regenerate wrapper files
- Ensure `gradlew` has execute permissions: `chmod +x gradlew`

### Monitoring stuck on default sensor values
- User must be authenticated before RTDB listener succeeds
- Verify Firebase Console → Realtime Database → `sensors` has `ph`, `tds`, `turbidity`
- Confirm the database URL in `FirebaseRealtimeSensorRepository.DATABASE_URL` matches Firebase Console (regional `asia-southeast1.firebasedatabase.app`)
- Filter Logcat for `HydroSenseRTDB` to see auth state, listener attach, `onDataChange`, or `onCancelled` (permission denied)

### Pump switches not responding
- User must be authenticated (same as sensor reads — RTDB rules require `auth != null`)
- Switch to MANUAL mode (toggle the Mode switch OFF) — pump switches are disabled in AUTO mode
- Check Serial Monitor for `[CTRL]` lines confirming the ESP32 is reading `/control` every 2s
- Check Firebase Console → `control` has `pumpA`, `pumpB`, and `auto` fields
- Check Firebase Console → `status` has `pumpA` and `pumpB` fields (written by ESP32)
- Filter Logcat for `HydroSenseRTDB` to see `/status` and `/control/auto` listener events
- Round-trip delay is 2–4 seconds (app writes → ESP32 reads → ESP32 writes status → app updates)
