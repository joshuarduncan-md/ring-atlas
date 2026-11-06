# Ring Atlas for Android

Ring Atlas is an independent, local Android app for viewing Oura Ring data. Version 0.3.0 adds encrypted backups, wearable comparison, FHIR R4 export, and a strength-training log.

## Install the update

Open the signed APK on Android 12 or newer and install it over your existing Ring Atlas installation. The APK uses the original package name and signing identity. Its additive database migrations preserve existing records.

## New in 0.3.0

| Feature | How to use it |
| --- | --- |
| Encrypted backup and restore | Open **Data → Encrypted backup and restore**. Choose a file destination and a passphrase of at least 12 characters. The backup includes local readings, raw ring history, sleep timelines, workouts, active lifting sessions, settings, imported comparison samples and journals. Restore authenticates the encrypted file and replaces the backed-up tables in one SQLite transaction. Ring keys and Android Bluetooth pairing are stored separately. Keep your passphrase to open the backup. |
| Wearable comparison | Open **Data → Compare wearables** and choose heart rate or RMSSD HRV. Choose a date range and load two sources. Bluetooth ring history, Android 14+ Health Connect sources with permission, and compatible CSV recordings appear separately. A chest strap can contribute through a companion app that writes to Health Connect or through a CSV. |
| FHIR export | Select a real data source and end date, then open **Data → Export FHIR R4**. Select readings and a range and save a JSON collection Bundle with a generated patient reference, coded observations, UCUM units, and provenance. Wearable summaries use Ring Atlas codes with a CodeSystem fragment included in the file. These are daily wearable summaries rather than clinical vital sign records. |
| Strength training | Open **Move → Open lifting log**. Configure the initial weights in **Setup**, then tap **Start workout A**. Tap a set to log the target and tap again to record fewer reps. Hold a set to enter exact reps or a different load. Finished sessions appear in both lifting history and the Ring Atlas activity log. |

Backups use AES-256-GCM with a random salt and nonce and PBKDF2-HMAC-SHA-256 at 600,000 iterations. Limits are 48 MB encrypted and 128 MB expanded. File providers and export destinations are chosen by the user.

Wearable CSV needs the header below and a UTC offset or Z on every timestamp. The file accepts bpm for hr and milliseconds for hrv.

```csv
timestamp,source,metric,value
2026-10-20T07:00:00-04:00,Chest strap,hr,62
2026-10-20T07:02:00-04:00,Chest strap,hr,65
```

Each source is averaged within aligned five-minute windows. Agreement statistics are calculated on paired windows and missing windows are counted separately. Different sampling conditions, different HRV methods, and clock drift can reduce comparability. The exported FHIR structure is validated against the HL7 R4 JSON schema. Receiving systems may need mappings for Ring Atlas summary codes.

## Strength training coverage

- Classic 5×5 A/B rotation with one 5-rep deadlift work set on B, lower-volume and assistance templates, and an A/B/C intermediate pause-lift template.
- Editable A/B/C workouts, custom exercises, timed exercises, per-set loads and rep edits, notes, and partial-workout saves.
- Configurable increases, missed-session deloads, and return-from-break reductions. Each exercise keeps its own progression.
- Adjustable successful/missed rest timers, Android notification actions, and optional precise alarms. The in-app countdown works independently of notification permission.
- Plate inventories by pair, bar weight, lb/kg conversion, inventory-aware load and warm-up calculations.
- A 28-day calendar, per-lift records, load, reps, volume and estimated 1RM charts, and set-level CSV export.
- Optional manual export of completed session timing to Health Connect on Android 14+.

The lifting module uses the public Stronglifts 5×5 workflow. This version leaves the full advanced program catalog, Madcow ramp programming, the full exercise video library, widgets, and smartwatch integration for later coverage. Ring Atlas is independent from Stronglifts.

[Stronglifts app feature list](https://stronglifts.com/app/) · [Classic 5×5 guide](https://stronglifts.com/stronglifts-5x5/workout-program/) · [Intermediate guide](https://stronglifts.com/stronglifts-5x5/intermediate/) · [HL7 FHIR R4 Bundle](https://hl7.org/fhir/R4/bundle.html)

## Pairing without a hexadecimal key

| Ring state | What to do |
| --- | --- |
| New or already factory-reset | Close Oura and wake the ring on its charger. In Ring Atlas, open **Data → Find nearby Oura rings** and choose the ring. Approve Android's Bluetooth pairing prompt. **Connect and sync** checks the ring's state, creates a key when appropriate, and stores it with Android Keystore. |
| Already paired and you have its key | Use **Data → Pairing help and key import**. Choose a 16-byte binary key file, a text file with 32 hexadecimal characters, or enter the key manually. The app stores it after the ring accepts it. |
| Already paired and you have no key | Sync or back up the ring in the official Oura app before resetting it there. Factory reset erases data still on the ring. Close Oura before it sets up the ring again, forget the old Android Bluetooth pairing if it remains, and pair in Ring Atlas. |

A provisioned ring verifies an existing key with a Bluetooth challenge. The challenge and Oura's data export provide no pairing key. Ring Atlas does not send a factory-reset command. Use **View saved recovery key** after successful setup if you want to keep a private copy.

[Oura factory-reset instructions](https://support.ouraring.com/hc/en-us/articles/360025446854-Factory-Reset-an-Oura-Product) · [Oura data export](https://support.ouraring.com/hc/en-us/articles/360025441594-Export-Share-Your-Oura-Data)

## Data and features

| Area | How it works |
| --- | --- |
| Direct Bluetooth | Experimental Oura Gen3, Ring 4 and Ring 5 protocol. Firmware and battery, a 25-second live HR session, latest cached HR/SpO₂ when available, and timestamped ring history. Compatible data can supply detected bed windows, 30-second sleep stages, HR/HRV, breathing, skin temperature, oxygen and activity intensity. Some firmware emits bed windows with no usable staging. |
| Oura files | Import supported daily CSV, JSON or a Membership Hub ZIP. Imported Oura scores are shown separately from Atlas scores. |
| Health Connect | On Android 14 or newer, tap **Data → Sync Oura from Health Connect**. It reads up to 30 days of Oura-written data and preserves supported workout labels. Oura requires active membership for its Health Connect integration with Gen3 and newer rings. |
| Atlas scoring | Daily Sleep, Sleep Opportunity, Readiness and Activity scores, plus a seven-day Fitness Habits Score and a Personal Fitness Index based on changes in repeat walk-test estimates. Open **Today → See score contributors** for values, baselines, and missing inputs. |
| Workouts | Direct ring sync can suggest sustained moderate/vigorous movement for review. A phone-tracking session can suggest walking, running or cycling from step cadence, accelerometer motion and optional GPS speed. Confirm or change the suggestion. Use the workout log for strength, swimming, yoga, rowing and other activities. |
| VO₂ max estimate | Enter a measured one-mile walk time, finish heart rate, age, weight and study equation under **Move**. Repeated estimates appear in Trends. |
| Additional tools | Data-based guidance, charts, journal and habit comparisons, rest mode, paced breathing, shareable seven-day summaries, and CSV exports of daily metrics, journal, workouts and raw Bluetooth events. |

Native ring features depend on model, firmware, which data is retained, and clock alignment. Ring-derived stage times are aligned to the last page in a coherent run of stage packets. Their timestamp and duration are estimates. Unusable stage sequences can still leave a bed window. Phone tracking runs while Ring Atlas is open and stores the workout and user edits. Optional GPS speed is used in memory and location points are discarded.

Remaining gaps include Oura's multi-sport ring-only classification, daily stress and resilience models, Cardiovascular Age, Advisor, cycle and pregnancy predictions, Health Radar, Health Panels, meals and glucose integration, social features, automatic background sync, firmware updates and entitlement-gated high-rate sensing. This Android implementation still requires a physical-ring test.

[Oura Health Connect guide](https://support.ouraring.com/hc/en-us/articles/10786105824531-Health-Connect-by-Android-Integration) · [Oura automatic activity detection](https://support.ouraring.com/hc/en-us/articles/360063022993-Automatic-Activity-Detection)

## Scoring methodology

- **Sleep** weights duration 65%, continuity 25% and duration regularity 10% when available. The 7-hour adult sleep reference comes from the AASM/SRS consensus. Continuity and regularity scoring curves are Atlas design choices. When time asleep is unavailable, an independent bed-window score uses an 8-hour opportunity target.
- **Readiness** uses same-source 28-day median baselines for HRV and resting or low overnight heart rate. It needs at least seven prior measurements of a contributing signal. HRV, heart rate and Sleep are weighted 45/35/20 when available. Individual thresholds and weights are Atlas design choices.
- **Activity** combines recorded activity with steps relative to a goal or baseline. Ring MET estimates use a 3 MET moderate threshold and 6 MET vigorous threshold. Ring days need at least 60 minutes of recorded MET samples to contribute moderate-equivalent minutes. Ring coverage is displayed since the rest of the day may be unobserved. When both source and local activity totals exist, Atlas uses the larger total to avoid counting the same movement twice.
- **Fitness habits** weights seven-day activity volume 60%, active days 25%, and sleep consistency 15% when sufficient data is present. The volume reference is 150 moderate-equivalent minutes per week. **Personal Fitness Index** combines this score (70%) with changes between two personal one-mile estimates at least 14 days apart (30%). A recent test within 45 days and sufficient activity data are required. Both are wellness indicators with Atlas-defined weights.
- **Cardio capacity** uses the Kline et al. (1987) one-mile walk regression, which was studied in healthy adults ages 30–69. The app converts estimated L/min to mL/kg/min. Walk-test estimates can vary with route, conditions, effort and measured heart rate.

Missing optional contributors are left out of weighted averages. Atlas score weights, trend thresholds, and workout classification rules are transparent heuristics. They have no independent clinical validation.

[AASM/SRS sleep consensus](https://pubmed.ncbi.nlm.nih.gov/26039963/) · [CDC adult activity targets](https://www.cdc.gov/physical-activity-basics/guidelines/adults.html) · [CDC activity intensity](https://www.cdc.gov/physical-activity-basics/measuring/index.html) · [Kline one-mile walk study](https://pubmed.ncbi.nlm.nih.gov/3600239/)

## Build and verify

Install JDK 17+, Python 3, zip, Android SDK Platform 35 and Build Tools 35.0.0. Set `ANDROID_SDK_ROOT` to the SDK. Set `RING_ATLAS_KEYSTORE` and `RING_ATLAS_KEYSTORE_PASSWORD_FILE` to your signing material before running the build script.

```bash
./build-android.sh
```

The script produces `out/RingAtlas-Android.apk`. Android requires the same signing identity for in-place updates. The source archive includes no signing credentials. For fixture checks on a desktop JVM, compile `Metrics.java`, `RingProtocol.java`, `RingSummary.java`, `OuraImport.java`, `AtlasScore.java`, `ActivityDetection.java` plus `BackupCipher.java`, `WearableCompare.java`, `FhirExport.java`, `StrengthPlan.java` and both files under `tests` with Java 17 and the `org.json:json` JAR. Run `com.ringatlas.app.AtlasChecks` and `com.ringatlas.app.AtlasFeatureChecks`. The latter can write a sample FHIR Bundle to a path passed as its first argument.

Protocol framing and fixtures draw on [open_oura](https://github.com/Th0rgal/open_oura). Stage assembly draws on [open_health](https://github.com/Th0rgal/open_health). Both are MIT licensed. Ring Atlas is unaffiliated with Oura.
