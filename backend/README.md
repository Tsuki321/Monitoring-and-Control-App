# HydroSense monthly analytics on Firebase Spark

The Android app collects detailed history locally and uploads closed months to Cloud Firestore. A phone computes monthly reports using the existing Kotlin ridge-regression model. No Cloud Functions, paid scheduler, or billing upgrade is required.

## Setup

The existing project is `database-for-hydrosense`. Its default Firestore database already exists in `nam5`; Realtime Database remains in `asia-southeast1`. Do not create or migrate a database.

From the repository root, with Firebase CLI signed in, deploy only database rules and indexes:

```powershell
firebase deploy --project database-for-hydrosense --only firestore,database
```

The production configuration in `firebase.json` intentionally excludes Functions. The old JavaScript implementation is retained only as a model parity fixture and reference. The old `database.rules.fragment.json` and `database.test.rules.json` are legacy files; deploy `backend/database.rules.json`.

All signed-in Firebase Authentication accounts in this project are trusted system operators, matching the existing pump controller. No manual membership documents are required. The rules preserve authenticated access to the existing `sensors`, `control`, and `status` paths and add scoped access for the `longos` shared state. Anonymous requests are denied. Firestore was empty with deny-all rules before this revision; the deployment does not replace another app's collections.

Install the signed release APK as an update to retain local history. Keep at least one signed-in phone connected and allow its existing background monitoring to run. Uninstalling or clearing app storage loses that phone's unuploaded journal. No unseen sensor samples can be recovered.

## Collection and data contract

- `systems/longos/current` in RTDB: current sample, stage wear, revisions, model coefficients, recorder lease, latest pump/alert transition, and a bounded 20-event maintenance buffer. A transaction chooses one recording session for 150 seconds; normal samples refresh once per minute. Event transitions can update sooner.
- Room `history`: one canonical reading per observed minute plus maintenance, command outcomes, pump, and alert events. Firmware counters recover measured runtime across gaps; missing water readings remain gaps.
- Firestore `systems/longos/months/YYYY-MM/uploads/{hash}`: immutable JSON batches of up to 100 records and 700,000 UTF-8 bytes. The batch and parent month revision commit atomically. Only a server read of the exact payload acknowledges local records. Shared content hashes and canonical record IDs make retries and overlapping phones safe for analytics.
- Parent month document: upload revision and month cutoff. A changed revision forces a running report calculation to retry.
- `.../jobs/report`: 15-minute processing lease. A crashed phone cannot hold the job forever.
- `.../analytics/summary`: historical summary that may improve after late uploads.
- `.../reports/official`: immutable date/status, coefficient vectors, source cutoff, input digest, observation IDs/digest, and coverage.
- `systems/longos/observations/{eventId}`: immutable qualifying exhaustion outcomes, added only with a closed-month archive batch.

Month boundaries use `Asia/Manila` (UTC+08:00). The recorder corrects phone time with RTDB's server offset; it timestamps observations, not the hardware's original acquisition time. No firmware modification is needed.

Synchronization runs on sign-in, manual sync, and a six-hour WorkManager schedule. Android or connectivity can delay it. A phone processes a closed month after all its pending uploads finish. Reports publish no earlier than 24 hours after month-end, provided a connected phone runs the job. Months without uploaded records have no report. Late data updates history and future training but never replaces an existing official date.

## Model and limitations

Each stage fits 400 reproducible baseline rows using ridge regression (lambda 0.01). Eight eligible observed exhaustion outcomes enable field adaptation at weight 25 per outcome, retaining the newest 32 per stage. Exhaustion labels require positive measured runtime, no rinses, no invalid or long-gap intervals, and at least 95% feature coverage. Preventive or damaged replacements remain history only.

A monthly date needs at least 60 valid minutes, valid samples covering at least 10% of the whole month, a valid final sample within 24 hours of month-end, and measured duty cycle at least 0.001. A service after the last sample withholds that stage's date. These are software eligibility guards, not validated confidence thresholds. Dates use the earlier runtime or calendar limit and display as Month Day, Year. Replacing a stage supersedes the old installation's date; rinsing retains the published date until the next report.

The first shared sample establishes a commissioning reference. Record actual new installations before collecting a service interval. Old per-phone filter preferences lack common event provenance and are not merged into authoritative shared history.

Phone calculations and user-reported outcomes are trusted operator inputs. Database rules enforce archive immutability, revision changes, and processing ownership; they cannot prove readings or calculated dates are scientifically correct. Generated-data R-squared is not field accuracy, and configured lifetimes are assumptions.

## Free quotas and verification

Firestore free quotas include 1 GiB stored data, 50,000 reads/day, and 20,000 writes/day. Batching reduces writes, but overlapping phone archives and years of retention consume storage. Firestore and RTDB each have their own usage limits. If quota is exhausted, sync retries and local records remain available. Review Firebase usage before increasing phone count or retention. Unlimited free retention is not promised.

Use `.github/workflows/revision_ci.yml` for signed Android builds, Kotlin model/history tests, JavaScript parity checks, and Firestore/RTDB emulator integration tests. Build only through GitHub Actions. Emulator cases cover signed-out denial, atomic retry-safe uploads, concurrent leases, expired lease recovery, immutable reports, late revisions, observations, and preservation of pump access.

Physical checks still require two phones and the ESP32: shared wear/revisions, service conflicts, background recording, reconnecting, and month selection. No physical month-end experiment is implied by automated tests.

See [the Word algorithm document](../docs/algorithm/HydroSense_Machine_Learning_Algorithm.docx), [Firestore quotas](https://firebase.google.com/docs/firestore/quotas), [transactions](https://firebase.google.com/docs/firestore/manage-data/transactions), and [WorkManager scheduling](https://developer.android.com/topic/libraries/architecture/workmanager/how-to/define-work).
