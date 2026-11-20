package com.remindermate

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import com.remindermate.data.ReminderDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("reminder_id", -1L)
        if (id <= 0L) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = ReminderDatabase.get(context).reminderDao()
                val reminder = dao.getById(id) ?: return@launch
                if (reminder.completed) return@launch
                when (intent.action) {
                    ACTION_DONE -> {
                        dao.update(reminder.copy(completed = true, snoozedUntil = 0L))
                        ReminderScheduler.cancel(context, id)
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
                        return@launch
                    }
                    ACTION_SNOOZE -> {
                        val delay = intent.getLongExtra(EXTRA_DELAY, 10 * 60 * 1000L)
                        val updated = reminder.copy(snoozedUntil = System.currentTimeMillis() + delay.coerceAtLeast(60_000L))
                        dao.update(updated)
                        ReminderScheduler.schedule(context, updated)
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
                        return@launch
                    }
                }
                if (reminder.snoozedUntil > 0L) dao.update(reminder.copy(snoozedUntil = 0L))
                showNotification(context, reminder.id, reminder.title, reminder.notes)
                val next = nextOccurrence(reminder.dueAt, reminder.repeat)
                if (next != null) {
                    val updated = reminder.copy(dueAt = next, snoozedUntil = 0L)
                    dao.update(updated)
                    ReminderScheduler.schedule(context, updated)
                }
            } finally { pending.finish() }
        }
    }

    private fun showNotification(context: Context, id: Long, title: String, notes: String) {
        val channelId = "activity_reminders_v2"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Reminder alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Time-sensitive activity reminders"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 250, 500, 250, 700)
                enableLights(true)
                lightColor = android.graphics.Color.CYAN
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
            }
            manager.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_REMINDER_ID, id)
        }
        val open = PendingIntent.getActivity(context, id.toInt(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun actionPending(action: String, request: Int, delay: Long = 0L): PendingIntent {
            val actionIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                putExtra("reminder_id", id)
                putExtra(EXTRA_DELAY, delay)
            }
            return PendingIntent.getBroadcast(context, request, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("ReminderMate • $title")
            .setContentText(notes.ifBlank { "Your activity is due now. Tap to manage this task." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { "Your activity is due now." }))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 500, 250, 500, 250, 700))
            .setLights(android.graphics.Color.CYAN, 1000, 1000)
            .setContentIntent(open)
            .addAction(0, "Snooze 10 min", actionPending(ACTION_SNOOZE, id.toInt() + 100000, 10 * 60 * 1000L))
            .addAction(0, "Snooze 1 hour", actionPending(ACTION_SNOOZE, id.toInt() + 200000, 60 * 60 * 1000L))
            .addAction(0, "Mark done", actionPending(ACTION_DONE, id.toInt() + 300000))
            .build()
        manager.notify(id.toInt(), notification)
    }

    private fun nextOccurrence(dueAt: Long, repeat: String): Long? {
        val field = when (repeat) { "Daily" -> Calendar.DAY_OF_YEAR; "Weekly" -> Calendar.WEEK_OF_YEAR; "Monthly" -> Calendar.MONTH; else -> return null }
        val calendar = Calendar.getInstance().apply { timeInMillis = dueAt }
        do { calendar.add(field, 1) } while (calendar.timeInMillis <= System.currentTimeMillis())
        return calendar.timeInMillis
    }
    companion object {
        const val ACTION_DONE = "com.remindermate.ACTION_DONE"
        const val ACTION_SNOOZE = "com.remindermate.ACTION_SNOOZE"
        const val EXTRA_DELAY = "snooze_delay"
    }
}
