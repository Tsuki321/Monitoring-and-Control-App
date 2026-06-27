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
- **Secrets:** Never commit credentials. `.gitignore` excludes `UID.txt` (ESP32 Firebase Auth credentials), `*.apk`/`*.aab` (build outputs), and `*.keystore`/`*.jks` (signing keys).

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

1. **FirebaseRealtimeSensorRepository** (`data/repository/`) — Listens to Firebase Realtime Database at `/sensors` via `ValueEventListener` wrapped in `callbackFlow`. Maps `ph`, `tds`, and `turbidity` into `SensorData`. Used by **Monitoring** only. Requires Firebase Auth (`auth != null` per RTDB rules). Connects to the regional database URL (`asia-southeast1.firebasedatabase.app`) and waits for a signed-in user before attaching the listener; logs under tag `HydroSenseRTDB`.

2. **MockSensorRepository** (`data/repository/`) — Singleton object providing simulated data for **Dashboard** and **Control**. Emits pH/TDS/turbidity on a timer only if wired to Monitoring (currently not). Tank fill level updates independently. Pump/valve state is managed via `MutableStateFlow`.

3. **Custom Views** (`ui/views/`)
   - `WaterTankView` — Animated water tank with fill level, surface wave animation (2.5s loop), and smooth fill transitions
   - `OceanWaveView` — Multi-layer parallax wave background using `Choreographer` for frame-perfect animation
   - Both use delta-time calculations to avoid Float precision loss (see "Animation System" below)

4. **Navigation** — Single-activity architecture with AndroidX Navigation Component. Bottom nav visible on main screens (Dashboard, Monitoring, Control), hidden on Settings/About/Auth pages. Top bar dynamically switches between settings gear and back arrow.

### Data Flow

- **Sensor readings (Monitoring):** `FirebaseRealtimeSensorRepository.sensorDataFlow` → `MonitoringViewModel` → `StateFlow` → `MonitoringFragment` (animated count-ups)
- **Tank status:** `MockSensorRepository.tankStatus` → `DashboardViewModel` → `WaterTankView.setFillPercent()`
- **Pump control:** Fragment → `ControlViewModel` → `MockSensorRepository.togglePumpA()` / valves → `_pumpState` → UI

### Realtime Database schema (current)

Project: `database-for-hydrosense` (see `app/google-services.json`).
Database URL: `https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app`

```json
{
  "sensors": {
    "ph": 7.2,
    "tds": 450,
    "turbidity": 12.5
  }
}
```

**Security rules (current):** `/sensors` read and write only when `auth != null`. The Android app reads after user sign-in. ESP32 cannot use the same rule without an authenticated client (see roadmap below).

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

**Next (phase 2b — ESP32 publish):**
1. **Hardware/firmware** — ESP32 reads pH, TDS, turbidity (ADC/I2C/etc.), Wi-Fi connect, periodic publish interval (e.g. every 5–30 s).
2. **Firebase write auth** — Approach chosen (dedicated device user, see phase 2a). Alternatives considered but rejected for now:
   - **Custom token / service account** (server or Cloud Function mints short-lived tokens for the device — more secure, more setup)
   - **Rules change for device path** — e.g. allow write to `/sensors` only when `auth != null` for app and a separate locked path for devices (avoid wide open `.write: true`)
3. **Payload contract** — ESP32 writes the same three fields under `/sensors` (optionally add `updatedAt` server timestamp or millis for staleness UI later).
4. **Arduino stack** — `Firebase ESP Client` or REST PATCH to RTDB with ID token; use `databaseURL` `https://database-for-hydrosense-default-rtdb.asia-southeast1.firebasedatabase.app` (no trailing slash).
5. **End-to-end test** — ESP32 publishing → Monitoring screen matches hardware readings without Console edits.

**Later (phase 3 — app parity):**
- Drive Dashboard `SensorStatus` online/offline from RTDB presence or `updatedAt` threshold
- Move tank/pump/valve to RTDB (or separate paths) and replace remaining `MockSensorRepository` usage
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
