package com.remindermate

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remindermate.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val dao = ReminderDatabase.get(this).reminderDao()
        setContent {
            val vm: ReminderViewModel = viewModel(factory = ReminderViewModel.Factory(dao, this))
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF3157A5), secondary = androidx.compose.ui.graphics.Color(0xFF5276BC))) {
                ReminderScreen(vm)
            }
        }
    }
}

class ReminderViewModel(private val dao: ReminderDao, private val context: android.content.Context) : ViewModel() {
    val reminders = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun save(item: Reminder) = viewModelScope.launch {
        if (item.id == 0L) {
            val id = dao.insert(item)
            ReminderScheduler.schedule(context, item.copy(id = id))
        } else {
            dao.update(item.copy(snoozedUntil = 0L))
            ReminderScheduler.schedule(context, item.copy(snoozedUntil = 0L))
        }
    }
    fun complete(item: Reminder) = viewModelScope.launch {
        dao.update(item.copy(completed = true, snoozedUntil = 0L))
        ReminderScheduler.cancel(context, item.id)
    }
    fun remove(item: Reminder) = viewModelScope.launch {
        dao.delete(item)
        ReminderScheduler.cancel(context, item.id)
    }
    fun snooze(item: Reminder, delay: Long) = viewModelScope.launch {
        val updated = item.copy(snoozedUntil = System.currentTimeMillis() + delay)
        dao.update(updated)
        ReminderScheduler.schedule(context, updated)
    }
    class Factory(private val dao: ReminderDao, private val context: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReminderViewModel(dao, context) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(vm: ReminderViewModel) {
    val reminders by vm.reminders.collectAsState()
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val visible = reminders.filter { item ->
        val matches = item.title.contains(query, true) || item.notes.contains(query, true)
        val status = when (filter) {
            "Today" -> !item.completed && item.dueAt in dayStart(now)..dayEnd(now)
            "Upcoming" -> !item.completed && item.dueAt > dayEnd(now)
            "Overdue" -> !item.completed && item.dueAt < now && item.snoozedUntil <= now
            "Completed" -> item.completed
            "Snoozed" -> !item.completed && item.snoozedUntil > now
            "Personal", "Professional" -> !item.completed && item.category == filter
            else -> !item.completed
        }
        matches && status
    }
    val pending = reminders.count { !it.completed }
    val overdue = reminders.count { !it.completed && it.dueAt < now && it.snoozedUntil <= now }
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("ReminderMate"); Text("Built by Anuj Awasthi", style = MaterialTheme.typography.labelSmall) } }) },
        floatingActionButton = { FloatingActionButton(onClick = { editing = null; showAdd = true }) { Icon(Icons.Default.Add, "Add activity") } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text("Your day at a glance", style = MaterialTheme.typography.titleMedium); Text("$pending active activities") }
                    Column(horizontalAlignment = Alignment.End) { Text("$overdue", style = MaterialTheme.typography.headlineMedium); Text("Overdue") }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search activities") })
            Spacer(Modifier.height(8.dp))
            val filters = listOf("All", "Today", "Upcoming", "Overdue", "Completed", "Snoozed")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.take(3).forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                filters.drop(3).forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Personal", "Professional").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
            }
            Spacer(Modifier.height(8.dp))
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here yet. Add an activity with +.") }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 90.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                                        Text("${item.category}  •  ${item.priority} priority", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (!item.completed) IconButton(onClick = { editing = item; showAdd = true }) { Icon(Icons.Default.Edit, "Edit") }
                                    IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Default.Delete, "Delete") }
                                }
                                Text(SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(item.dueAt)), style = MaterialTheme.typography.bodySmall)
                                if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodyMedium)
                                if (item.snoozedUntil > now) Text("Snoozed until " + SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.snoozedUntil)), color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (!item.completed) {
                                        TextButton(onClick = { vm.complete(item) }) { Icon(Icons.Default.Done, null); Spacer(Modifier.width(4.dp)); Text("Mark done") }
                                        var snoozeMenu by remember { mutableStateOf(false) }
                                        Box {
                                            TextButton(onClick = { snoozeMenu = true }) { Text("Snooze") }
                                            DropdownMenu(expanded = snoozeMenu, onDismissRequest = { snoozeMenu = false }) {
                                                listOf("5 min" to 5L, "10 min" to 10L, "15 min" to 15L, "30 min" to 30L, "1 hour" to 60L, "2 hours" to 120L, "Tomorrow" to 1440L, "2 days" to 2880L, "7 days" to 10080L).forEach { (label, minutes) ->
                                                    DropdownMenuItem(text = { Text(label) }, onClick = { vm.snooze(item, minutes * 60_000L); snoozeMenu = false })
                                                }
                                            }
                                        }
                                    } else Text("Completed", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddReminderDialog(initial = editing, onDismiss = { showAdd = false; editing = null }, onSave = {
        vm.save(it); showAdd = false; editing = null
    })
}

private fun dayStart(time: Long): Long = Calendar.getInstance().apply { timeInMillis = time; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
private fun dayEnd(time: Long): Long = dayStart(time) + 86_400_000L - 1

@Composable
fun AddReminderDialog(initial: Reminder? = null, onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var category by remember(initial?.id) { mutableStateOf(initial?.category ?: "Personal") }
    var priority by remember(initial?.id) { mutableStateOf(initial?.priority ?: "Medium") }
    var repeat by remember(initial?.id) { mutableStateOf(initial?.repeat ?: "Never") }
    var dueAt by remember(initial?.id) { mutableLongStateOf(initial?.dueAt ?: System.currentTimeMillis() + 60 * 60 * 1000) }
    val dateText = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(dueAt))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New activity" else "Edit activity") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Activity title *") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Description / notes") })
                Text("Category")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == "Personal", { category = "Personal" }, label = { Text("Personal") })
                    FilterChip(category == "Professional", { category = "Professional" }, label = { Text("Professional") })
                }
                Text("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Low", "Medium", "High").forEach { FilterChip(priority == it, { priority = it }, label = { Text(it) }) }
                }
                Text("Reminder: $dateText")
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = dueAt }
                    DatePickerDialog(context, { _, y, m, d ->
                        val updated = Calendar.getInstance().apply { timeInMillis = dueAt; set(y, m, d) }
                        TimePickerDialog(context, { _, h, min ->
                            updated.set(Calendar.HOUR_OF_DAY, h); updated.set(Calendar.MINUTE, min); updated.set(Calendar.SECOND, 0)
                            dueAt = updated.timeInMillis
                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Choose date & time") }
                Text("Repeat")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Never", "Daily", "Weekly", "Monthly").forEach { FilterChip(repeat == it, { repeat = it }, label = { Text(it) }) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(Reminder(id = initial?.id ?: 0L, title = title.trim(), notes = notes.trim(), category = category,
                    priority = priority, dueAt = dueAt, repeat = repeat))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
