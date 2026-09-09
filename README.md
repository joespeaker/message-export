# SMS Export to Drive

Android app that reads SMS/MMS text messages on-device and exports them as a single
JSON file, overwriting the same file in a Google Drive folder on a recurring schedule.
Read-only, sideloaded, not a default-SMS-app replacement.

## What it does

- Reads all SMS (`Telephony.Sms`) and MMS (`Telephony.Mms` + `part` + `addr`) messages,
  extracting only the `text/plain` part of MMS (media-only MMS are skipped).
- Resolves each address to a contact name via `ContactsContract.PhoneLookup` where possible.
- Merges everything into one JSON array, sorted by timestamp ascending, and overwrites a
  single fixed file (`all-messages.json`) in a specific Drive folder - same file ID every
  run, no timestamped duplicates.
- Authenticates to Drive as a Google Cloud **service account** (JWT signed on-device,
  exchanged for a bearer token) so it can run unattended with no one present to click
  through a consent screen.
- Runs automatically every 24 hours via `WorkManager`, plus a manual "Run Export Now"
  button, and reschedules itself after a reboot.

## Output format

```json
[
  {
    "thread_id": 12345,
    "date": "2026-09-09T14:32:00Z",
    "direction": "sent",
    "address": "+18165551234",
    "contact_name": "Hannah Zahn",
    "body": "message text",
    "message_type": "sms"
  }
]
```

- `date` is ISO-8601 UTC.
- `direction` is `"sent"` or `"received"`.
- `address` is comma-separated for group MMS.
- `contact_name` is `null` when unresolved.
- `message_type` is `"sms"` or `"mms"`.

## Known limitations (by design, not bugs)

- **RCS "Restricted" messages**: end-to-end encrypted RCS messages are marked
  `Restricted` by Android and unreadable by any app that isn't the current default SMS
  handler. These are silently skipped - same gap the previous SMS Backup & Restore
  workflow had.
- **No media export**: MMS photos/videos are never exported, text content only.
- **~24h schedule, not exact midnight**: `WorkManager`'s periodic schedule is anchored to
  first-run time and can drift under battery optimization. An `AlarmManager`
  exact-alarm approach would fix this but is a bigger change and out of scope unless
  requested.

## External setup (one-time, manual - not part of this app)

1. **Create a Google Cloud project** (or reuse one) and enable the **Google Drive API**
   for it.
2. **Create a service account** in that project (IAM & Admin → Service Accounts), then
   create and download a JSON key for it.
3. **Place the key** at `app/src/main/assets/service-account.json` before building.
   `app/src/main/assets/service-account.json.example` shows the expected shape. The
   real key is gitignored - never commit it.
4. **Share the target Drive folder** (`1d64WeKvcvZduR_AaIsdKbNXbYQj_LOd7`, "text
   backups") with the service account's `client_email` as **Editor**. Without this
   step every export will fail with a permission error.
5. **Sideload the built APK** onto the phone (not distributed via Play Store).
6. On first launch, grant the `READ_SMS` and `READ_CONTACTS` permissions when prompted.
7. **Disable battery optimization** for the app (Settings → Apps → SMS Export to
   Drive → Battery) so the nightly background job isn't deferred or killed.

## Building

Standard Gradle/Android Studio project, no special setup beyond placing the service
account key described above.

```
./gradlew assembleRelease
```

Min SDK 26. Only `READ_SMS` and `READ_CONTACTS` are requested at runtime; the app never
requests default-SMS-app status and has no compose/send/receive UI.

## Project layout

- `MessageReader.kt` / `ContactResolver.kt` - reads and merges SMS + MMS, resolves names.
- `Message.kt` - the message model and JSON serialization.
- `ServiceAccountAuth.kt` - JWT construction/signing and OAuth2 token exchange.
- `DriveUploader.kt` - creates the Drive file once, then overwrites its content by ID.
- `ExportManager.kt` - orchestrates one full export run and records the outcome.
- `ExportWorker.kt` / `ExportScheduler.kt` / `BootCompletedReceiver.kt` - WorkManager
  scheduling (periodic + manual + reboot re-arm).
- `MainActivity.kt` - permission requests, manual run button, last-run status.
