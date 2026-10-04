# Grid v2: M5 (Backup & restore) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans. Executed inline; archive, CSV and Drive client are test-first.

**Goal:** WhatsApp-style backup to the hidden Google Drive app folder, plus local backup files and CSV export (spec §3.10).

**Architecture:**
- **`BackupArchive`:** builds and reads a zip containing `manifest.json` + `grid.db` + `settings.json`.
- **`BackupManager`:** takes a consistent snapshot via `VACUUM INTO`, validates on restore, then swaps the DB file and restarts.
- **Drive access:**
  - `DriveAuth`: the Google Identity `AuthorizationClient`, scope `drive.appdata`.
  - `DriveClient`: OkHttp against the Drive v3 REST API (list/upload/download/delete/about).
  - `DriveBackup`: orchestrates the two.
- **`BackupWorker`:** periodic, constrained to Wi-Fi when the user asks.
- **UI:** a Backup screen and a restore entry on onboarding's welcome page.

## Tasks

### 5.1 Archive + manifest (TDD)
- `BackupManifest(formatVersion, schemaVersion, appVersion, createdAt, device, currency, counts)`.
- `BackupArchive.write(out, dbFile, settingsJson, manifest)` and `BackupArchive.read(input, tempDir)` → `RestoredArchive(manifest, dbFile, settingsJson)`.
- **Rejected inputs:** an unknown format version, a newer schema, a missing entry, or an entry name containing path traversal.

### 5.2 CSV export (TDD)
RFC 4180 quoting, ISO dates, decimal amounts with sign by type, category, method, merchant, note, source.

### 5.3 BackupManager (Robolectric)
- **Snapshot:** `VACUUM INTO` a cache file, then settings JSON (`SettingsRepository.exportJson/importJson`), then manifest counts.
- **Restore:**
  1. Validate the archive, including `PRAGMA user_version` and `PRAGMA integrity_check` on the extracted DB.
  2. Close Room and keep `grid.db.bak`.
  3. Copy the new DB in and drop `-wal`/`-shm`.
  4. Import the settings.
  5. Restart via AlarmManager.
- **Test:** add a transaction, back up, delete the transaction, restore into a fresh file, and check the transaction is back.

### 5.4 Drive
- **`DriveClient` (MockWebServer tests):**
  - `list()`: `spaces=appDataFolder`, fields id, name, size, createdTime, appProperties
  - `upload(name, bytes, appProperties)`: multipart, `parents=[appDataFolder]`
  - `download(id)` and `delete(id)`
  - `user()`: `about?fields=user`
  - Errors map to `DriveError.Unauthorized | Network | Http(code)`
- **`DriveAuth`:** `authorize(interactive)` returns `Token | NeedsConsent(IntentSender) | NotConfigured | Failed`. Status 10 (DEVELOPER_ERROR) maps to `NotConfigured`.
- **`DriveBackup`:**
  - `backupNow(token)`: upload, keep the latest 2, store lastAt/lastSize/account.
  - `latest(token)` and `restoreLatest(token)`.

### 5.5 Worker + settings
- **Backup settings:** frequency (Off / Daily / Weekly / Monthly), Wi-Fi only, connected, account, lastAt, lastSize, lastError.
- **`BackupWorker`:** silent authorization. If consent is needed, it posts a "Reconnect Google Drive" notification. It's rescheduled whenever the settings change.

### 5.6 UI
- **Backup screen:**
  - Google Drive card: connect, account, last backup, Back up now with progress, frequency chips, Wi-Fi only, Restore.
  - "Not configured" explanation linking to `docs/GOOGLE_DRIVE_SETUP.md`.
  - Local card: Save backup file (SAF create), Restore from file (SAF open), Export CSV.
  - The restore confirmation dialog warns that it replaces current data.
- **Onboarding welcome:** a "Restore a backup" link (Drive or file).
- **Settings entry:** "Backup & restore" showing the last backup time.

### 5.7 Docs
- `docs/GOOGLE_DRIVE_SETUP.md`: GCP project, enable the Drive API, OAuth consent screen, Android OAuth client with package `com.grid.app` and the debug/release SHA-1.
- `scripts/sha1.ps1` prints the SHA-1.

### 5.8 Verification
- Unit and Robolectric tests.
- **E2E:**
  1. The Backup screen renders.
  2. "Back up now" on an unconfigured build shows the not-configured state and doesn't crash.
  3. CSV export through the system picker writes a file, checked via `adb shell ls` of the picked folder.
