package com.remindermate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remindermate.data.Reminder
import com.remindermate.data.ReminderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReminderDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reminderId = intent.getLongExtra(MainActivity.EXTRA_OPEN_REMINDER_ID, -1L)
        if (reminderId <= 0L) { finish(); return }
        val dao = ReminderDatabase.get(this).reminderDao()
        setContent {
            var reminder by remember { mutableStateOf<Reminder?>(null) }
            var loading by remember { mutableStateOf(true) }
            val scope = rememberCoroutineScope()
            LaunchedEffect(reminderId) {
                reminder = withContext(Dispatchers.IO) { dao.getById(reminderId) }
                loading = false
            }
            MaterialTheme(colorScheme = reminderMateColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("REMINDERMATE", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Your reminder", style = MaterialTheme.typography.headlineMedium)
                        when {
                            loading -> CircularProgressIndicator()
                            reminder == null -> Text("This activity no longer exists.")
                            else -> {
                                val item = reminder!!
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text(item.title, style = MaterialTheme.typography.headlineSmall)
                                        Text("${item.category} • ${item.priority} priority", color = MaterialTheme.colorScheme.primary)
                                        if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodyLarge)
                                        Text("Due: " + SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(item.dueAt)))
                                        if (item.snoozedUntil > System.currentTimeMillis()) Text("Snoozed until " + SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.snoozedUntil)))
                                    }
                                }
                                Text("Snooze this activity", style = MaterialTheme.typography.titleMedium)
                                val options = listOf("5 min" to 5L, "10 min" to 10L, "15 min" to 15L, "30 min" to 30L, "1 hour" to 60L, "2 hours" to 120L, "Tomorrow" to 1440L, "2 days" to 2880L, "7 days" to 10080L)
                                options.chunked(3).forEach { group ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        group.forEach { (label, minutes) ->
                                            OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), onClick = {
                                                scope.launch {
                                                    val updated = item.copy(snoozedUntil = System.currentTimeMillis() + minutes * 60_000L)
                                                    withContext(Dispatchers.IO) { dao.update(updated); ReminderScheduler.schedule(this@ReminderDetailActivity, updated) }
                                                    finish()
                                                }
                                            }) { Text(label) }
                                        }
                                    }
                                }
                                Button(modifier = Modifier.fillMaxWidth(), onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            dao.update(item.copy(completed = true, snoozedUntil = 0L))
                                            ReminderScheduler.cancel(this@ReminderDetailActivity, item.id)
                                        }
                                        (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(item.id.toInt())
                                        finish()
                                    }
                                }) { Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Mark as done") }
                                TextButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                            }
                        }
                    }
                }
            }
        }
    }
}
