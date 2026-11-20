# ReminderMate upgrade

## Implemented in this feature branch
- Dashboard with activity counts, overdue count, search, and status/category filters.
- Create and edit activities with Personal/Professional categories, priority, due date/time, and Never/Daily/Weekly/Monthly recurrence.
- Mark done, delete, and snooze activities from the dashboard.
- Notification actions for snooze (10 minutes / 1 hour) and mark done.
- Persist snooze state in Room with a version 1 to 2 migration.
- Reschedule pending reminders after device reboot using the existing boot receiver.
- Preserve the main branch; changes are isolated to feature/remindermate-pro-upgrade.

## Build
GitHub Actions workflow builds a debug APK and uploads it as the ReminderMate-debug-apk artifact.
