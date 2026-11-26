package com.remindermate

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.remindermate.data.Reminder
import com.remindermate.data.ReminderDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class VoiceAssistantActivity : ComponentActivity() {
    private var onPermissionGranted: (() -> Unit)? = null
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onPermissionGranted?.invoke()
    }

    fun requestAudioPermission(onGranted: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            onPermissionGranted = onGranted
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = reminderMateColorScheme()) { VoiceCreateScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceCreateScreen(activity: VoiceAssistantActivity) {
    val context = LocalContext.current
    val dao = remember { ReminderDatabase.get(context).reminderDao() }
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf("en-IN") }
    var listening by remember { mutableStateOf(false) }
    var processing by remember { mutableStateOf(false) }
    var spokenText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Personal") }
    var priority by remember { mutableStateOf("Medium") }
    var repeat by remember { mutableStateOf("Never") }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var suggestedDate by remember { mutableStateOf<Long?>(null) }
    var leadMinutes by remember { mutableIntStateOf(0) }
    var timeNeedsConfirmation by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose a language and speak your reminder.") }
    var showConfirmation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun applyRecognizedText(text: String) {
        spokenText = text
        val draft = VoiceReminderParser.parse(text, language)
        title = draft.title
        notes = draft.notes
        category = draft.category
        priority = draft.priority
        repeat = draft.repeat
        dueAt = draft.dueAt
        suggestedDate = draft.dateAtStartOfDay
        leadMinutes = draft.notificationLeadMinutes
        timeNeedsConfirmation = draft.timeNeedsConfirmation
        processing = false
        showConfirmation = true
        status = when {
            draft.dueAt == null && draft.dateAtStartOfDay != null -> "Date captured. Please choose or confirm the time."
            draft.dueAt == null -> "Date or time not recognized. Please complete both before saving."
            draft.timeNeedsConfirmation -> "Time captured, but AM/PM is ambiguous. Please verify it below."
            else -> "All details extracted. Review before saving."
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add by Voice", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("What should I remind you?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Speak naturally in English or Hindi. You will review the details before anything is saved.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "en-IN", onClick = { language = "en-IN" }, label = { Text("English") })
                FilterChip(selected = language == "hi-IN", onClick = { language = "hi-IN" }, label = { Text("हिंदी") })
            }
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(144.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, contentDescription = "Voice input", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                }
            }
            Text(status, style = MaterialTheme.typography.titleMedium)
            Button(
                enabled = !processing,
                onClick = {
                    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                        status = "Speech recognition is unavailable. Please type your reminder below."
                        showConfirmation = true
                    } else {
                        activity.requestAudioPermission {
                            try {
                                speechRecognizer?.destroy()
                                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { recognizer ->
                                    recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
                                        override fun onReadyForSpeech(params: Bundle?) { listening = true; status = "Listening… speak your reminder." }
                                        override fun onBeginningOfSpeech() { listening = true }
                                        override fun onRmsChanged(rmsdB: Float) {}
                                        override fun onBufferReceived(buffer: ByteArray?) {}
                                        override fun onEndOfSpeech() { listening = false; processing = true; status = "Understanding your request…" }
                                        override fun onError(error: Int) {
                                            listening = false; processing = false
                                            status = "Couldn't recognize speech. Please try again or type your reminder."
                                        }
                                        override fun onResults(results: Bundle?) {
                                            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                            if (!matches.isNullOrEmpty()) applyRecognizedText(matches.first())
                                            else { processing = false; status = "No speech detected. Please try again." }
                                        }
                                        override fun onPartialResults(partialResults: Bundle?) {
                                            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                                            if (!partial.isNullOrBlank()) spokenText = partial
                                        }
                                        override fun onEvent(eventType: Int, params: Bundle?) {}
                                    })
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                                    }
                                    recognizer.startListening(intent)
                                }
                            } catch (_: Exception) {
                                listening = false; processing = false
                                status = "Voice input couldn't start. Please use manual typing."
                                showConfirmation = true
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Mic, null)
                Spacer(Modifier.width(8.dp))
                Text(if (listening) "Listening…" else "Start speaking")
            }

            if (spokenText.isNotBlank()) {
                OutlinedTextField(spokenText, { spokenText = it }, label = { Text("Recognized speech") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }

            if (showConfirmation) {
                HorizontalDivider()
                Text("Review reminder", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                OutlinedTextField(title, { title = it }, label = { Text("Activity title *") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Text("Category")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == "Personal", { category = "Personal" }, label = { Text("Personal") })
                    FilterChip(category == "Professional", { category = "Professional" }, label = { Text("Professional") })
                }
                Text("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach { p -> FilterChip(priority == p, { priority = p }, label = { Text(p) }) }
                }
                Text("Repeat")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Never", "Daily", "Weekly", "Monthly").forEach { value -> FilterChip(repeat == value, { repeat = value }, label = { Text(value) }) }
                }
                OutlinedButton(
                    onClick = {
                        val initial = Calendar.getInstance().apply { timeInMillis = dueAt ?: suggestedDate ?: System.currentTimeMillis() + 3_600_000L }
                        DatePickerDialog(context, { _, year, month, day ->
                            val chosen = Calendar.getInstance().apply {
                                timeInMillis = dueAt ?: suggestedDate ?: System.currentTimeMillis() + 3_600_000L
                                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            }
                            TimePickerDialog(context, { _, hour, minute ->
                                chosen.set(Calendar.HOUR_OF_DAY, hour); chosen.set(Calendar.MINUTE, minute); chosen.set(Calendar.SECOND, 0); chosen.set(Calendar.MILLISECOND, 0)
                                dueAt = chosen.timeInMillis
                                suggestedDate = null
                                timeNeedsConfirmation = false
                            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), false).show()
                        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(dueAt?.let { "Reminder: " + SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault()).format(Date(it)) } ?: "Choose date and time *") }
                OutlinedTextField(leadMinutes.toString(), { value -> leadMinutes = value.toIntOrNull()?.coerceIn(0, 10080) ?: 0 }, label = { Text("Notify before (minutes)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (timeNeedsConfirmation) Text("AM/PM was not clearly spoken. Use the date/time picker to verify the exact time.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text("Recognized text and all reminder details must be checked before saving.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = title.isNotBlank() && dueAt != null && dueAt!! > System.currentTimeMillis() && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val reminder = Reminder(
                                    title = title.trim(),
                                    notes = notes.trim(),
                                    category = category,
                                    priority = priority,
                                    dueAt = dueAt!!,
                                    repeat = repeat,
                                    notificationLeadMinutes = leadMinutes
                                )
                                val id = dao.insert(reminder)
                                ReminderScheduler.schedule(context, reminder.copy(id = id))
                                status = "Reminder saved successfully."
                                showConfirmation = false
                                activity.finish()
                            } catch (_: Exception) {
                                status = "Couldn't save the reminder. Please try again."
                                saving = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (saving) "Saving…" else "Confirm and save") }
                OutlinedButton(onClick = { showConfirmation = false; spokenText = ""; title = ""; notes = ""; dueAt = null; suggestedDate = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
            if (!showConfirmation) {
                OutlinedButton(onClick = { showConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Keyboard, null); Spacer(Modifier.width(8.dp)); Text("Type reminder instead")
                }
                if (status.contains("unavailable") || status.contains("type")) {
                    OutlinedTextField(title, { title = it }, label = { Text("Activity title") }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

