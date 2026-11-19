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

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getLongExtra("reminder_id", -1L)
        if (reminderId <= 0L) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val reminder = ReminderDatabase.get(context).reminderDao().getById(reminderId)
                    ?: return@launch
                // A completed or deleted activity must never generate another alert.
                if (reminder.completed) return@launch

                val channelId = "activity_reminders"
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            channelId,
                            "Activity reminders",
                            NotificationManager.IMPORTANCE_HIGH
                        )
                    )
                }
                val openApp = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(context, channelId)
                    .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                    .setContentTitle(reminder.title)
                    .setContentText(reminder.notes.ifBlank { "This activity is still pending." })
                    .setStyle(NotificationCompat.BigTextStyle().bigText(
                        reminder.notes.ifBlank { "This activity is still pending. Mark it completed in ReminderMate to stop repeat alerts." }
                    ))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setContentIntent(openApp)
                    .build()
                manager.notify(reminderId.toInt(), notification)

                // Repeat every 30 minutes until the user marks this activity completed.
                scheduleFollowUp(context, reminderId, 30 * 60 * 1000L)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun scheduleFollowUp(context: Context, reminderId: Long, delayMs: Long) {
        val nextIntent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_id", reminderId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId.toInt(),
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }
}
