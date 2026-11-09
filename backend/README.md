# HydroSense monthly analytics deployment

This revision adds Room history on Android, shared current state in Realtime Database, and immutable monthly archives/reports in Cloud Firestore. Backend code is prepared here; it is not deployed by an app build or by the test workflow.

## Configuration and deployment

1. In the existing `database-for-hydrosense` Firebase project, enable the Blaze plan and create the default Cloud Firestore database. Select the appropriate region before creating it. Functions use `asia-southeast1`, matching the existing Realtime Database. Set a billing budget and alerts appropriate to your deployment.
2. Install the Firebase CLI in your deployment environment and authenticate with the project owner account. In `backend/functions`, run `npm install`. Deploy from the repository root with `firebase deploy --project database-for-hydrosense --only functions:monthly-analytics,firestore`. Review any existing Firestore rules first: the included file governs the new system archive and otherwise denies access. Merge existing application collections if this project has other consumers.
3. Merge `database.rules.fragment.json` under the existing RTDB `rules` object, retaining the existing `/sensors`, `/status`, and `/control` access policies. **Remove any permissive root/ancestor `.write` rule**: RTDB grants cascade and cannot be revoked by a child rule. Only the Admin SDK may write `systems/longos/current`, `official`, `maintenance`, `results`, and the membership registry. Do not replace the firmware's rules without checking its authentication.
4. For each authorized user's Firebase Authentication UID, create RTDB `/systemMembers/longos/{uid}: true` and Firestore `/systems/longos/members/{uid}` with `{ "enabled": true }`, using the console or Admin SDK. Clients cannot enroll themselves. Remove both memberships to revoke access. These memberships identify users of the same physical installation; they do not follow a phone identifier.
5. Install the verified APK. Confirm `/systems/longos/current/sample` starts appearing when the ESP32 changes `/sensors`. No firmware source modification is needed: the function assigns event time and a stable event ID. Existing raw live readings and controls keep using their original paths.
6. Confirm two authorized phones display the same filter state, a maintenance request receives a result, and local history records appear. An unapproved account must receive permission-denied errors for shared history and archive operations.

The deployment environment needs Firebase credentials and Blaze billing. No production deployment, billing change, membership grant, or security-rule replacement is performed by the Android workflow.

## Data contract

All month boundaries use `Asia/Manila` (UTC+08:00). Millisecond event timestamps use UTC. `/sensors` remains the source; the backend expects `ph`, `tds`, `turbidity`, cumulative `runtimeA`/`runtimeB` seconds and optional tank/leak values. Numeric strings and the existing JSON-string sensor payload are accepted.

- `systems/longos/current`: latest source sample, cumulative filter state, stage revisions, model coefficients, and idempotency receipts. No detailed sensor history is stored here.
- `systems/longos/maintenance`: server-confirmed service events. Current maintenance and state synchronize during the month as requested. The detailed archive still uploads only after month-end.
- Local Room `history`: one received sample per source minute, observed alert/status/control events, command outcomes, and maintenance. Unobserved periods remain missing. Repeated delivery uses stable IDs. Local history survives app restarts but not app-data deletion/uninstallation; never uninstall the recorder to upgrade it.
- Firestore `systems/longos/months/YYYY-MM/uploads`: immutable, content-addressed batches of at most 100 records. Uploaders retain local copies and acknowledge only after a confirmed write. The ingester records `ingested`/`rejected` status and accepted/rejected counts.
- `.../records`: normalized records with immutable IDs. Duplicate samples across phones are counted once per source minute for analytics. Every distinct original source record remains available for audit.
- `.../analytics/summary`: historical analytics, updated when late records arrive.
- `.../reports/official`: immutable forecast, source cutoff, publication time, input digest, observation IDs/digest, version, coefficients, coverage, and per-stage due dates.
- `systems/longos/observations`: end-of-life observations derived from server-confirmed maintenance; only received monthly uploads add these records.

Closed-month uploads run on app sign-in, manual sync, and a six-hour WorkManager schedule. Android can defer work. The server runs every six hours and allows a 24-hour collection window after month-end. It then publishes using the data received, even if coverage is insufficient. An empty month publishes an insufficient-data result. If an offline phone later uploads, historical analytics improve but the official forecast remains fixed. The newly available training observations participate in the next published month.

## Model and operational limitations

The backend ports the Kotlin synthetic generator (400 rows per stage, pinned 64-bit LCG), ridge solver (lambda 0.01), and weighting (25 per qualifying field row, threshold eight). Real labels now require an explicit exhausted/end-of-life report, positive measured hours, no rinses, and complete measured coverage. Preventive/damage replacements remain history and do not teach the model premature exhaustion. The former Kotlin implementation used predicted usage >=85% as an eligibility gate; the monthly pipeline uses observed service condition instead.

The first source event establishes the commissioning reference, not evidence that existing physical media are new. Record actual new installations using Replace before gathering a full service interval. Existing per-phone preference history is preserved on the phone but is not merged as authoritative state because it lacks shared sample IDs and observed installation provenance.

An official date needs at least 60 sampled minutes, at least 10% coverage of the month, a valid recent sample within the last 24 hours of the month, and an observable pump duty cycle. These are conservative software publication guards, not experimentally validated accuracy thresholds. Sensor fault values remain visible in history but cannot establish a usable last prediction sample. Long gaps accrue recovered runtime at neutral wear and disqualify that service interval from training. Gaps are never filled with invented sensor readings.

Published dates are based on the last archived state, observed monthly duty cycle, average archived water conditions, and calendar rating. Forecasts do not retrain on raw sensor rows as if they were measured lifespan labels. A replacement supersedes the old filter's date immediately; the new installation waits for the next monthly forecast. A rinse is recorded immediately and retains the published date until the next month. Live health/alerts may change while the date remains fixed.

Generated-data R-squared is a synthetic fit diagnostic, not field accuracy. Filter ratings and modeled rinse recovery are configured assumptions. Reusable stages can require rinsing or replacement depending on calendar age. Dates are formatted in the selected locale as Month Day, Year, and stored as ISO local dates in the report.

## Verification and rollout

Use `.github/workflows/revision_ci.yml` for Android build/unit checks and backend model tests. No compilation or tests run locally. Before production rollout, exercise the Firebase Emulator Suite or a staging project: membership denial, duplicate batch delivery, function retry after maintenance commit, missing telemetry, two phones replacing simultaneously, an offline month boundary, a late historical upload, and a rejected malformed batch. Check Firestore rules in the emulator before merging them into an existing project. Verify the generated APK on two devices for navigation, background recording, month selection, and recovery after a network interruption.

Cloud Functions incur work for every source change; frequency therefore affects cost. Archive batches reduce phone upload writes, but ingestion writes individual records for queryable history. Existing maintenance receipts and history are retained; define a retention/export policy before years of production use. This version supports one configured system (`longos`), matching the existing single-installation telemetry paths.

References: [Room](https://developer.android.com/training/data-storage/room), [WorkManager scheduling](https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work), [RTDB triggers](https://firebase.google.com/docs/functions/database-events), [Firestore transactions](https://firebase.google.com/docs/firestore/manage-data/transactions), [Cloud Functions deployment](https://firebase.google.com/docs/functions/get-started).
