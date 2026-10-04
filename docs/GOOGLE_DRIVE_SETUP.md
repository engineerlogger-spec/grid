# Google Drive backup setup (one time, ~10 minutes)

Grid backs up to your Google Drive's hidden **app data folder**, the same place WhatsApp keeps its backups. You can't see the files in drive.google.com, and only Grid can read them. Google requires every app that does this to register itself once in a Google Cloud project. Until that's done, the Backup screen shows "Drive backup isn't set up for this build" and you can still use **Backup file** (local save/restore).

The scope Grid uses is `https://www.googleapis.com/auth/drive.appdata`. Google classifies it as **non-sensitive**, so no app verification or security review is needed.

## 1. Create a project and enable the Drive API
1. Go to <https://console.cloud.google.com/> and sign in with the Google account you'll back up to.
2. Use the project picker → **New project** → name it `Grid` → **Create**.
3. Open **APIs & Services → Library**, search for **Google Drive API**, and click **Enable**.

## 2. OAuth consent screen
1. Open **APIs & Services → OAuth consent screen** (labelled **Google Auth Platform → Branding** in newer consoles).
2. Set **User type: External**, **App name: Grid**, and your email as the support and developer contact.
3. Under **Scopes**, add `.../auth/drive.appdata`.
4. Under **Audience / Test users**, add your own Google account.
   - While the app is in *Testing*, only listed test users can connect.
   - Because the scope is non-sensitive, you can also press **Publish app**. No review is triggered.

## 3. Android OAuth client
1. Open **APIs & Services → Credentials → Create credentials → OAuth client ID**.
2. Set **Application type: Android** and **Package name: `com.grid.app`**.
3. **SHA-1 certificate fingerprint:** run `.\scripts\sha1.ps1`. For the debug key on the development PC (as of 2026-10-04) it is:
   ```
   77:99:2A:7E:06:FB:98:D5:B0:7B:69:C9:27:2D:FA:CD:02:A1:AB:CF
   ```
   A release build signed with a different key needs a **second** Android client with that key's SHA-1: `.\scripts\sha1.ps1 -Keystore path\to\release.jks -Alias <alias>`.
4. Click **Create**. No client ID or secret goes into the app: Google matches the package name and signing certificate automatically.

## 4. Connect in the app
**Settings → Backup & restore → Connect Google Drive.** Pick your account and allow access to "its own configuration data in your Google Drive". Grid makes a first backup right away, then follows the schedule you choose (Daily / Weekly / Monthly, optionally Wi-Fi only).

## Troubleshooting
| Symptom | Fix |
|---|---|
| "Drive backup isn't set up for this build" | The package name or SHA-1 in step 3 doesn't match the installed build. Re-check with `scripts\sha1.ps1`. |
| Consent screen says "access blocked / app not verified" | Add your account as a test user (step 2.4), or publish the app. |
| Backups stopped and a "Reconnect Google Drive" notification appeared | Access was revoked or expired. Open the app and tap **Back up now**. |
| Restoring on a new phone | Install Grid, then on the welcome screen tap **Restore a backup → Restore from Google Drive**. |
