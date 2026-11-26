package com.remindermate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.remindermate.data.Reminder
import com.remindermate.data.ReminderDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

private enum class VoiceAction { COMPLETE, SNOOZE, DELETE, RESCHEDULE, PRIORITY }

private data class ManagementCommand(
    val action: VoiceAction,
    val targetText: String,
    val snoozeMinutes: Long = 10,
    val dueAt: Long? = null,
    val priority: String? = null
)

class VoiceManageActivity : ComponentActivity() {
    private var onPermissionGranted: (() -> Unit)? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onPermissionGranted?.invoke()
    }

    fun listen(language: String, onText: (String) -> Unit, onError: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onPermissionGranted = { listen(language, onText, onError) }
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech recognition is unavailable. Use the command examples or try again when speech services are available.")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { recognizer.destroy(); onError("Couldn't recognize speech. Please try again or use the text command.") }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (spoken.isNullOrBlank()) onError("No speech detected. Please try again.") else onText(spoken)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = reminderMateColorScheme()) { VoiceManageScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceManageScreen(activity: VoiceManageActivity) {
    val context = LocalContext.current
    val dao = remember { ReminderDatabase.get(context).reminderDao() }
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf("en-IN") }
    var commandText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Say what you want to do with an existing reminder.") }
    var pending by remember { mutableStateOf<ManagementCommand?>(null) }
    var target by remember { mutableStateOf<Reminder?>(null) }
    var candidates by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var allReminders by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        dao.observeAll().collect { allReminders = it.filter { item -> !item.completed } }
    }

    fun interpret(spoken: String) {
        commandText = spoken
        val parsed = parseManagementCommand(spoken, language)
        if (parsed == null) {
            pending = null
            target = null
            status = "I couldn't identify the action. Try: 'Snooze dentist appointment for 30 minutes' or 'Mark report submission done'."
            return
        }
        val words = parsed.targetText.lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        val ranked = allReminders.map { reminder ->
            val titleWords = (reminder.title + " " + reminder.notes).lowercase(Locale.ROOT)
                .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
            val score = words.count { it in titleWords }
            reminder to score
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        val best = ranked.firstOrNull()?.second ?: 0
        val matches = ranked.filter { it.second == best }.map { it.first }
        if (matches.isEmpty()) {
            pending = null
            target = null
            candidates = emptyList()
            status = "No active reminder matched that description. Please say its title more clearly."
        } else {
            pending = parsed
            candidates = matches
            target = if (matches.size == 1) matches.first() else null
            status = if (matches.size == 1) "Please review the action and confirm." else "More than one reminder matches. Select the correct one."
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Manage by Voice") },
            navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Default.ArrowBack, "Back") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Control existing reminders", style = MaterialTheme.typography.headlineSmall)
            Text("English and Hindi commands are supported. Every change is shown for confirmation before it is applied.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "en-IN", onClick = { language = "en-IN" }, label = { Text("English") })
                FilterChip(selected = language == "hi-IN", onClick = { language = "hi-IN" }, label = { Text("हिंदी") })
            }
            Button(
                enabled = !busy,
                onClick = { activity.listen(language, { interpret(it) }, { status = it }) },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Speak command") }
            OutlinedTextField(
                value = commandText,
                onValueChange = { commandText = it },
                label = { Text("Or type a command") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            OutlinedButton(onClick = { interpret(commandText) }, enabled = commandText.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Understand command")
            }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (pending != null && candidates.size > 1 && target == null) {
                Text("Choose the reminder")
                candidates.forEach { item ->
                    OutlinedButton(onClick = { target = item }, modifier = Modifier.fillMaxWidth()) {
                        Text(item.title + " • " + item.priority)
                    }
                }
            }
            val command = pending
            val selected = target
            if (command != null && selected != null) {
                HorizontalDivider()
                Text("Confirm this change", style = MaterialTheme.typography.titleLarge)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(selected.title, style = MaterialTheme.typography.titleMedium)
                        Text("Action: " + when (command.action) {
                            VoiceAction.COMPLETE -> "Mark as completed"
                            VoiceAction.SNOOZE -> "Snooze for ${command.snoozeMinutes} minutes"
                            VoiceAction.DELETE -> "Delete permanently"
                            VoiceAction.RESCHEDULE -> "Reschedule to " + java.text.SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault()).format(java.util.Date(command.dueAt!!))
                            VoiceAction.PRIORITY -> "Change priority to ${command.priority}"
                        })
                        Text("Original due: " + java.text.SimpleDateFormat("EEE, dd MMM • hh:mm a", Locale.getDefault()).format(java.util.Date(selected.dueAt)))
                    }
                }
                if (command.action == VoiceAction.DELETE) {
                    Text("This deletion cannot be undone.", color = MaterialTheme.colorScheme.error)
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    when (command.action) {
                                        VoiceAction.COMPLETE -> {
                                            dao.update(selected.copy(completed = true, snoozedUntil = 0L))
                                            ReminderScheduler.cancel(context, selected.id)
                                        }
                                        VoiceAction.SNOOZE -> {
                                            val updated = selected.copy(snoozedUntil = System.currentTimeMillis() + command.snoozeMinutes * 60_000L)
                                            dao.update(updated)
                                            ReminderScheduler.schedule(context, updated)
                                        }
                                        VoiceAction.DELETE -> {
                                            dao.delete(selected)
                                            ReminderScheduler.cancel(context, selected.id)
                                        }
                                        VoiceAction.RESCHEDULE -> {
                                            val updated = selected.copy(dueAt = command.dueAt!!, snoozedUntil = 0L)
                                            dao.update(updated)
                                            ReminderScheduler.schedule(context, updated)
                                        }
                                        VoiceAction.PRIORITY -> dao.update(selected.copy(priority = command.priority!!))
                                    }
                                }
                                status = "Done. The reminder was updated successfully."
                                pending = null
                                target = null
                                candidates = emptyList()
                                commandText = ""
                            } catch (_: Exception) {
                                status = "The change couldn't be saved. Please try again."
                            } finally { busy = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Applying…" else "Confirm and apply") }
                OutlinedButton(
                    onClick = { pending = null; target = null; candidates = emptyList(); status = "Action cancelled. No changes were made." },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Cancel") }
            }
            Text("Examples", style = MaterialTheme.typography.titleMedium)
            Text("• Mark the dentist appointment done\n• Snooze the project call for 30 minutes\n• Delete the grocery reminder\n• Move the bank meeting to tomorrow at 4 pm\n• Set the report reminder to high priority\n\n• मीटिंग को पूरा हुआ मार्क करो\n• डॉक्टर की अपॉइंटमेंट 30 मिनट के लिए स्नूज़ करो\n• रिपोर्ट की रिमाइंडर कल शाम 5 बजे कर दो")
        }
    }
}

private fun parseManagementCommand(raw: String, language: String): ManagementCommand? {
    val text = raw.lowercase(Locale.ROOT).trim()
    val action = when {
        listOf("delete", "remove", "हटा", "डिलीट").any { text.contains(it) } -> VoiceAction.DELETE
        listOf("snooze", "postpone", "later", "स्नूज़", "बाद में").any { text.contains(it) } -> VoiceAction.SNOOZE
        listOf("complete", "completed", "mark done", "finish", "done", "पूरा", "हो गया", "समाप्त").any { text.contains(it) } -> VoiceAction.COMPLETE
        listOf("priority", "प्राथमिकता").any { text.contains(it) } -> VoiceAction.PRIORITY
        listOf("reschedule", "move", "change time", "tomorrow", "आज", "कल", "परसों", "बजे").any { text.contains(it) } -> VoiceAction.RESCHEDULE
        else -> return null
    }
    val priority = when {
        text.contains("high") || text.contains("उच्च") -> "High"
        text.contains("low") || text.contains("कम") -> "Low"
        text.contains("medium") || text.contains("मध्यम") -> "Medium"
        else -> null
    }
    if (action == VoiceAction.PRIORITY && priority == null) return null
    val duration = Regex("(\\d{1,4})\\s*(minutes?|mins?|hours?|hrs?|मिनट|घंटे)").find(text)
    val snoozeMinutes = duration?.let {
        val amount = it.groupValues[1].toLongOrNull() ?: 10L
        if (it.groupValues[2].startsWith("hour") || it.groupValues[2].startsWith("hr") || it.groupValues[2] == "घंटे") amount * 60 else amount
    } ?: 10L
    val dueAt = if (action == VoiceAction.RESCHEDULE) parseRequestedDateTime(text) else null
    if (action == VoiceAction.RESCHEDULE && dueAt == null) return null
    var targetText = text
        .replace(Regex("(?i)\\b(remind me to|please|delete|remove|snooze|postpone|complete|completed|mark|done|finish|move|reschedule|change|priority|set|the|a|an|reminder|task|to|for|at|on|tomorrow|today|day after tomorrow|high|medium|low)\\b"), " ")
        .replace(Regex("\\d{1,4}\\s*(minutes?|mins?|hours?|hrs?)"), " ")
        .replace(Regex("\\b\\d{1,2}(:\\d{2})?\\s*(am|pm)\\b"), " ")
        .replace(Regex("कल|परसों|आज|सुबह|दोपहर|शाम|रात|बजे|मिनट|घंटे|मुझे|याद दिलाना|कर दो|करना|पूरा|हुआ|मार्क करो|हटा दो|डिलीट करो|स्नूज़ करो|प्राथमिकता"), " ")
        .replace(Regex("\\s+"), " ").trim()
    if (targetText.isBlank()) targetText = text
    return ManagementCommand(action, targetText, snoozeMinutes.coerceIn(1, 10080), dueAt, priority)
}

private fun parseRequestedDateTime(text: String): Long? {
    val calendar = Calendar.getInstance()
    when {
        text.contains("day after tomorrow") || text.contains("परसों") -> calendar.add(Calendar.DAY_OF_YEAR, 2)
        text.contains("tomorrow") || text.contains("कल") -> calendar.add(Calendar.DAY_OF_YEAR, 1)
        text.contains("today") || text.contains("आज") -> Unit
        else -> return null
    }
    val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)|(?:सुबह|प्रातः|दोपहर|शाम|रात)\\s*(\\d{1,2})(?::(\\d{2}))?\\s*बजे?").find(text) ?: return null
    var hour = (match.groupValues[1].ifBlank { match.groupValues[4] }).toIntOrNull() ?: return null
    val minute = (match.groupValues[2].ifBlank { match.groupValues[5] }).toIntOrNull() ?: 0
    val marker = match.groupValues[3]
    if (marker == "pm" && hour < 12) hour += 12
    if (marker == "am" && hour == 12) hour = 0
    if (text.contains("शाम") || text.contains("रात") || text.contains("दोपहर")) if (hour in 1..11) hour += 12
    if (text.contains("सुबह") && hour == 12) hour = 0
    if (hour !in 0..23 || minute !in 0..59) return null
    calendar.set(Calendar.HOUR_OF_DAY, hour)
    calendar.set(Calendar.MINUTE, minute)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis.takeIf { it > System.currentTimeMillis() }
}
