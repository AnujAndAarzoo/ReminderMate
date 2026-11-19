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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remindermate.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val dao = ReminderDatabase.get(this).reminderDao()
        setContent {
            val vm: ReminderViewModel = viewModel(factory = ReminderViewModel.Factory(dao, this))
            MaterialTheme { ReminderScreen(vm) }
        }
    }
}

class ReminderViewModel(private val dao: ReminderDao, private val context: android.content.Context) : ViewModel() {
    val reminders = dao.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun add(item: Reminder) = viewModelScope.launch {
        val id = dao.insert(item)
        ReminderScheduler.schedule(context, item.copy(id = id))
    }
    fun complete(item: Reminder) = viewModelScope.launch {
        dao.update(item.copy(completed = true))
        ReminderScheduler.cancel(context, item.id)
    }
    fun remove(item: Reminder) = viewModelScope.launch {
        dao.delete(item)
        ReminderScheduler.cancel(context, item.id)
    }
    class Factory(private val dao: ReminderDao, private val context: android.content.Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ReminderViewModel(dao, context) as T
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderScreen(vm: ReminderViewModel) {
    val reminders by vm.reminders.collectAsState()
    var showAdd by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("All") }
    val visible = reminders.filter {
        (filter == "All" || it.category == filter) && !it.completed
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("ReminderMate") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add activity")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp)) {
            Text("Your activities", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("${visible.size} pending activities", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("All", "Personal", "Professional").forEachIndexed { index, item ->
                    SegmentedButton(
                        selected = filter == item,
                        onClick = { filter = item },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)
                    ) { Text(item) }
                }
            }
            Spacer(Modifier.height(12.dp))
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No pending activities. Tap + to add one.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(visible, key = { it.id }) { item ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    Text("${item.category} • ${item.priority}", style = MaterialTheme.typography.labelMedium)
                                    Text(SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(item.dueAt)),
                                        style = MaterialTheme.typography.bodySmall)
                                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                                }
                                IconButton(onClick = { vm.complete(item) }) {
                                    Icon(Icons.Default.Done, contentDescription = "Complete")
                                }
                                IconButton(onClick = { vm.remove(item) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAdd) AddReminderDialog(onDismiss = { showAdd = false }, onSave = {
        vm.add(it); showAdd = false
    })
}

@Composable
fun AddReminderDialog(onDismiss: () -> Unit, onSave: (Reminder) -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var priority by remember { mutableStateOf("Medium") }
    var repeat by remember { mutableStateOf("Never") }
    var dueAt by remember { mutableLongStateOf(System.currentTimeMillis() + 60 * 60 * 1000) }
    val dateText = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(dueAt))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New activity") },
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
                    listOf("Low", "Medium", "High").forEach {
                        FilterChip(priority == it, { priority = it }, label = { Text(it) })
                    }
                }
                Text("Reminder: $dateText")
                OutlinedButton(onClick = {
                    val c = Calendar.getInstance().apply { timeInMillis = dueAt }
                    DatePickerDialog(context, { _, y, m, d ->
                        val updated = Calendar.getInstance().apply {
                            timeInMillis = dueAt; set(y, m, d)
                        }
                        TimePickerDialog(context, { _, h, min ->
                            updated.set(Calendar.HOUR_OF_DAY, h); updated.set(Calendar.MINUTE, min)
                            updated.set(Calendar.SECOND, 0)
                            dueAt = updated.timeInMillis
                        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), false).show()
                    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
                }) { Text("Choose date & time") }
                Text("Repeat")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Never", "Daily", "Weekly").forEach {
                        FilterChip(repeat == it, { repeat = it }, label = { Text(it) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                onSave(Reminder(title = title.trim(), notes = notes.trim(), category = category,
                    priority = priority, dueAt = dueAt, repeat = repeat))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
