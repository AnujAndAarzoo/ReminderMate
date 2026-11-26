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

private data class VoiceReminderDraft(
    val spokenText: String,
    val title: String,
    val category: String,
    val priority: String,
    val dueAt: Long?,
    val notificationLeadMinutes: Int
)

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
    var category by remember { mutableStateOf("Personal") }
    var priority by remember { mutableStateOf("Medium") }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var leadMinutes by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Choose a language and speak your reminder.") }
    var showConfirmation by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var speechRecognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    fun applyRecognizedText(text: String) {
        spokenText = text
        val draft = parseVoiceReminder(text, language)
        title = draft.title
        category = draft.category
        priority = draft.priority
        dueAt = draft.dueAt
        leadMinutes = draft.notificationLeadMinutes
        processing = false
        showConfirmation = true
        status = if (draft.dueAt == null) "Please choose the reminder date and time." else "Review every detail before saving."
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
                Text("Category")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(category == "Personal", { category = "Personal" }, label = { Text("Personal") })
                    FilterChip(category == "Professional", { category = "Professional" }, label = { Text("Professional") })
                }
                Text("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Medium", "High").forEach { p -> FilterChip(priority == p, { priority = p }, label = { Text(p) }) }
                }
                OutlinedButton(
                    onClick = {
                        val initial = Calendar.getInstance().apply { timeInMillis = dueAt ?: System.currentTimeMillis() + 3_600_000L }
                        DatePickerDialog(context, { _, year, month, day ->
                            val chosen = Calendar.getInstance().apply {
                                timeInMillis = dueAt ?: System.currentTimeMillis() + 3_600_000L
                                set(Calendar.YEAR, year); set(Calendar.MONTH, month); set(Calendar.DAY_OF_MONTH, day)
                            }
                            TimePickerDialog(context, { _, hour, minute ->
                                chosen.set(Calendar.HOUR_OF_DAY, hour); chosen.set(Calendar.MINUTE, minute); chosen.set(Calendar.SECOND, 0); chosen.set(Calendar.MILLISECOND, 0)
                                dueAt = chosen.timeInMillis
                            }, initial.get(Calendar.HOUR_OF_DAY), initial.get(Calendar.MINUTE), false).show()
                        }, initial.get(Calendar.YEAR), initial.get(Calendar.MONTH), initial.get(Calendar.DAY_OF_MONTH)).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(dueAt?.let { "Reminder: " + SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault()).format(Date(it)) } ?: "Choose date and time *") }
                OutlinedTextField(leadMinutes.toString(), { value -> leadMinutes = value.toIntOrNull()?.coerceIn(0, 10080) ?: 0 }, label = { Text("Notify before (minutes)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Text("Recognized text and all reminder details must be checked before saving.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = title.isNotBlank() && dueAt != null && dueAt!! > System.currentTimeMillis() && !saving,
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                val reminder = Reminder(
                                    title = title.trim(),
                                    category = category,
                                    priority = priority,
                                    dueAt = dueAt!!,
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
                OutlinedButton(onClick = { showConfirmation = false; spokenText = ""; title = ""; dueAt = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
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

private fun parseVoiceReminder(text: String, language: String): VoiceReminderDraft {
    val lower = text.lowercase(Locale.ROOT)
    val hindi = language.startsWith("hi")
    var due: Long? = null
    val calendar = Calendar.getInstance()
    when {
        lower.contains("day after tomorrow") || lower.contains("परसों") -> calendar.add(Calendar.DAY_OF_YEAR, 2)
        lower.contains("tomorrow") || lower.contains("कल") -> calendar.add(Calendar.DAY_OF_YEAR, 1)
        lower.contains("today") || lower.contains("आज") -> Unit
        else -> Unit
    }
    val timeRegex = Regex("""(?i)(\d{1,2})(?::(\d{2}))?\s*(am|pm)|(?:सुबह|प्रातः|दोपहर|शाम|रात)\s*(\d{1,2})(?::(\d{2}))?\s*बजे?""")
    val match = timeRegex.find(text)
    if (match != null) {
        var hour = (match.groupValues[1].ifBlank { match.groupValues[4] }).toIntOrNull() ?: 9
        val minute = (match.groupValues[2].ifBlank { match.groupValues[5] }).toIntOrNull() ?: 0
        val marker = match.groupValues[3].lowercase()
        if (marker == "pm" && hour < 12) hour += 12
        if (marker == "am" && hour == 12) hour = 0
        if (text.contains("शाम") || text.contains("रात") || text.contains("दोपहर")) if (hour in 1..11) hour += 12
        if (text.contains("सुबह") && hour == 12) hour = 0
        calendar.set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        calendar.set(Calendar.MINUTE, minute.coerceIn(0, 59))
        calendar.set(Calendar.SECOND, 0); calendar.set(Calendar.MILLISECOND, 0)
        due = calendar.timeInMillis
        if (due <= System.currentTimeMillis() && !lower.contains("today") && !lower.contains("आज") && !lower.contains("tomorrow") && !lower.contains("कल")) {
            calendar.add(Calendar.DAY_OF_YEAR, 1); due = calendar.timeInMillis
        }
    }
    val lead = Regex("""(?i)(\d{1,4})\s*(minutes?|mins?|मिनट)\s*(before|पहले)""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    var title = text
        .replace(Regex("""(?i)remind me to|remind me|please remind me to|add (a )?(work )?task to|create (a )?reminder to"""), "")
        .replace(Regex("""(?i)tomorrow|day after tomorrow|today|at \d{1,2}(:\d{2})?\s*(am|pm)|\d{1,2}(:\d{2})?\s*(am|pm)|notify me \d+ minutes? before|\d+ minutes? before|high priority|medium priority|low priority"""), "")
        .replace(Regex("""कल|परसों|आज|सुबह|दोपहर|शाम|रात|\d{1,2}(?::\d{2})?\s*बजे?|\d+ मिनट पहले|याद दिलाना|याद दिला देना|मुझे"""), "")
        .trim(' ', ',', '.', '।')
    if (title.isBlank()) title = text.trim()
    val category = if (lower.contains("work") || lower.contains("office") || lower.contains("professional") || lower.contains("काम")) "Professional" else "Personal"
    val priority = when {
        lower.contains("high priority") || lower.contains("उच्च प्राथमिकता") -> "High"
        lower.contains("low priority") || lower.contains("कम प्राथमिकता") -> "Low"
        else -> "Medium"
    }
    return VoiceReminderDraft(text, title, category, priority, due, lead)
}
