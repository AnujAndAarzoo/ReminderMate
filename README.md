# ReminderMate — Android MVP

A private, single-user Android reminder app for personal and professional activities.

## Included in this starter
- Native Android app using Kotlin and Jetpack Compose
- Local Room/SQLite persistence
- Personal and Professional categories
- Priority, notes, date/time selection
- One-time reminder notifications
- Automatic repeat alert every 30 minutes after the reminder fires, until the activity is marked completed or deleted
- Mark complete and delete
- Re-schedule pending reminders after device reboot
- No account, backend, or paid cloud subscription

## Build
1. Install Android Studio (current stable) and Android SDK 35.
2. Open this folder in Android Studio.
3. Allow Gradle sync.
4. Run on an Android 8.0+ device or emulator.
5. Allow notification permission when prompted.

## Important MVP limitations / next implementation steps
- Daily/Weekly calendar recurrence is not implemented yet. The separate overdue follow-up behaviour is implemented: after the due-time alert, the app repeats an alert every 30 minutes until the activity is completed or deleted.
- Exact alarm delivery depends on Android version, battery optimization and exact-alarm access. The app falls back to inexact idle-aware alarms when exact-alarm access is unavailable.
- Some manufacturers aggressively restrict background alarms; test on the target phone.
- Android 13+ requires notification permission.
- Add edit activity, overdue/completed views, snooze, custom repeat rules, backup/export, and tests before production release.
- This is a source project, not a signed APK. Build a signed release APK from Android Studio for installation/distribution.

## Build APK using GitHub Actions from a phone
1. Create a GitHub repository and upload the complete contents of this project (including `.github/workflows/build-apk.yml`).
2. Open the repository's Actions tab.
3. Select "Build ReminderMate APK" and tap "Run workflow" (or push to `main`).
4. Open the completed workflow run and download the `ReminderMate-debug-apk` artifact.
5. Extract the downloaded artifact ZIP; install `app-debug.apk` on the Android phone.
This cloud build requires internet access and a GitHub account. The debug APK is suitable for personal testing.
