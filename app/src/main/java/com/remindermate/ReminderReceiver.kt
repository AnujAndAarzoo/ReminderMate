package com.remindermate

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                        val until = System.currentTimeMillis() + delay.coerceAtLeast(60_000L)
                        val updated = reminder.copy(snoozedUntil = until)
                        dao.update(updated)
                        ReminderScheduler.schedule(context, updated)
                        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id.toInt())
                        return@launch
                    }
                }

                // If this was a snoozed alarm, clear the snooze marker before showing it.
                if (reminder.snoozedUntil > 0L) dao.update(reminder.copy(snoozedUntil = 0L))
                showNotification(context, reminder.id, reminder.title, reminder.notes)

                // Recurring reminders advance to their next calendar occurrence.
                val next = nextOccurrence(reminder.dueAt, reminder.repeat)
                if (next != null) {
                    val updated = reminder.copy(dueAt = next, snoozedUntil = 0L)
                    dao.update(updated)
                    ReminderScheduler.schedule(context, updated)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showNotification(context: Context, id: Long, title: String, notes: String) {
        val channelId = "activity_reminders"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Activity reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = PendingIntent.getActivity(
            context, id.toInt(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun actionPending(action: String, request: Int, delay: Long = 0L): PendingIntent {
            val actionIntent = Intent(context, ReminderReceiver::class.java).apply {
                this.action = action
                putExtra("reminder_id", id)
                putExtra(EXTRA_DELAY, delay)
            }
            return PendingIntent.getBroadcast(
                context, request, actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(notes.ifBlank { "This activity is due now." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { "This activity is due now." }))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Snooze 10 min", actionPending(ACTION_SNOOZE, id.toInt() + 100000, 10 * 60 * 1000L))
            .addAction(0, "Snooze 1 hour", actionPending(ACTION_SNOOZE, id.toInt() + 200000, 60 * 60 * 1000L))
            .addAction(0, "Mark done", actionPending(ACTION_DONE, id.toInt() + 300000))
            .build()
        manager.notify(id.toInt(), notification)
    }

    private fun nextOccurrence(dueAt: Long, repeat: String): Long? {
        val field = when (repeat) {
            "Daily" -> Calendar.DAY_OF_YEAR
            "Weekly" -> Calendar.WEEK_OF_YEAR
            "Monthly" -> Calendar.MONTH
            else -> return null
        }
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
