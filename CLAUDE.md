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
Repository (MockSensorRepository)
    │ emits data via Kotlin Flow
    ▼
Data Models (SensorData, TankStatus, PumpState, etc.)
```

**Key architectural components:**

1. **MockSensorRepository** (`data/repository/`) — Singleton object providing simulated sensor data via Kotlin `Flow`. Emits pH, TDS, Turbidity every 3 seconds using sine-wave oscillation with random jitter. Tank fill level updates independently. Pump/valve state is managed via `MutableStateFlow`.

2. **Custom Views** (`ui/views/`)
   - `WaterTankView` — Animated water tank with fill level, surface wave animation (2.5s loop), and smooth fill transitions
   - `OceanWaveView` — Multi-layer parallax wave background using `Choreographer` for frame-perfect animation
   - Both use delta-time calculations to avoid Float precision loss (see "Animation System" below)

3. **Navigation** — Single-activity architecture with AndroidX Navigation Component. Bottom nav visible on main screens (Dashboard, Monitoring, Control), hidden on Settings/About/Auth pages. Top bar dynamically switches between settings gear and back arrow.

### Data Flow

- **Sensor readings:** `MockSensorRepository.sensorDataFlow` → `ViewModel` collects in `viewModelScope` → emits to `StateFlow` → Fragment observes → updates UI with animated count-ups
- **Tank status:** `MockSensorRepository.tankStatus` → ViewModel observes → `WaterTankView.setFillPercent()`
- **Pump control:** Fragment → ViewModel → `MockSensorRepository.togglePumpA()` → updates `_pumpState` StateFlow → UI reflects change

### Swapping to Real Backend

To connect real sensors/hardware:

1. Create a new repository class implementing the same Flow-based interface as `MockSensorRepository`
2. Replace the repository instance in ViewModels (or use dependency injection)
3. Keep the ViewModel and UI layers unchanged — they only depend on the Flow interface

Example integration points:
- **REST API:** Use Retrofit + coroutines, emit responses to Flow
- **MQTT:** Use Paho client, convert messages to `SensorData` and emit
- **Bluetooth LE:** Use Android BLE APIs, parse characteristics into data models

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

The app includes Firebase Authentication and Firestore:

- **Auth:** `LoginFragment`, `RegisterFragment` handle email/password and Google Sign-In
- **Firestore:** Configured but not actively used in the current sensor flow (mock data is local-only)
- **google-services.json:** Required for Firebase features; must be placed in `app/` directory (not committed to repo)

If Firebase features fail, ensure `google-services.json` is present and matches the package name `com.watermonitor.app`.

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
- **Firebase BOM:** 34.0.0

To update dependencies, edit `libs.versions.toml` and sync Gradle.

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
