package com.remindermate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.remindermate.data.Reminder
import com.remindermate.data.ReminderDatabase
import kotlinx.coroutines.flow.collect
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class ReminderQuery { TODAY, UPCOMING, OVERDUE, PENDING, HIGH_PRIORITY, SUMMARY, DETAIL }
private data class QueryResult(val heading: String, val answer: String, val reminders: List<Reminder>)

class VoiceQueryActivity : ComponentActivity() {
    private var permissionContinuation: (() -> Unit)? = null
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) permissionContinuation?.invoke()
    }

    fun listen(language: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionContinuation = { listen(language, onResult, onError) }
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onError("Speech recognition is unavailable. Type your question below.")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { recognizer.destroy(); onError("I couldn't recognize that. Please try again or type your question.") }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (spoken.isNullOrBlank()) onError("No speech detected. Please try again.") else onResult(spoken)
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
        setContent { MaterialTheme(colorScheme = reminderMateColorScheme()) { VoiceQueryScreen(this) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceQueryScreen(activity: VoiceQueryActivity) {
    val context = LocalContext.current
    val dao = remember { ReminderDatabase.get(context).reminderDao() }
    var language by remember { mutableStateOf("en-IN") }
    var question by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ask about today's tasks, upcoming reminders, overdue items or priorities.") }
    var result by remember { mutableStateOf<QueryResult?>(null) }
    var allReminders by remember { mutableStateOf<List<Reminder>>(emptyList()) }
    var listening by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { code ->
            ttsReady = code == TextToSpeech.SUCCESS
            if (code == TextToSpeech.SUCCESS) engineLanguage(tts, language)
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null; ttsReady = false }
    }
    LaunchedEffect(Unit) { dao.observeAll().collect { allReminders = it } }

    fun runQuery(raw: String) {
        question = raw
        val parsed = classifyReminderQuery(raw)
        if (parsed == null) {
            result = null
            status = if (language == "hi-IN") "सवाल समझ नहीं आया। आज के काम, आने वाले रिमाइंडर, ओवरड्यू या प्राथमिकता के बारे में पूछें।"
                else "I couldn't identify that question. Try today's tasks, upcoming reminders, overdue tasks or high priority."
            return
        }
        val now = System.currentTimeMillis()
        val start = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = start + 86_400_000L - 1
        val pending = allReminders.filter { !it.completed }
        val selected: List<Reminder>
        val heading: String
        when (parsed.first) {
            ReminderQuery.TODAY -> { selected = pending.filter { it.dueAt in start..end }.sortedBy { it.dueAt }; heading = "Today's tasks" }
            ReminderQuery.UPCOMING -> { selected = pending.filter { it.dueAt > end }.sortedBy { it.dueAt }; heading = "Upcoming reminders" }
            ReminderQuery.OVERDUE -> { selected = pending.filter { it.dueAt < now && it.snoozedUntil <= now }.sortedBy { it.dueAt }; heading = "Overdue tasks" }
            ReminderQuery.PENDING -> { selected = pending.sortedBy { it.dueAt }; heading = "Pending tasks" }
            ReminderQuery.HIGH_PRIORITY -> { selected = pending.filter { it.priority.equals("High", true) }.sortedBy { it.dueAt }; heading = "High-priority tasks" }
            ReminderQuery.SUMMARY -> { selected = pending.sortedBy { it.dueAt }; heading = "Your task summary" }
            ReminderQuery.DETAIL -> {
                val ranked = pending.map { item ->
                    val searchable = (item.title + " " + item.notes).lowercase(Locale.ROOT)
                    item to parsed.second.count { searchable.contains(it) }
                }.filter { it.second > 0 }.sortedByDescending { it.second }
                val best = ranked.firstOrNull()?.second ?: 0
                selected = ranked.filter { it.second == best }.map { it.first }
                heading = "Reminder details"
            }
        }
        val answer = if (parsed.first == ReminderQuery.SUMMARY) {
            val overdueCount = pending.count { it.dueAt < now && it.snoozedUntil <= now }
            val todayCount = pending.count { it.dueAt in start..end }
            val highCount = pending.count { it.priority == "High" }
            val upcomingCount = pending.count { it.dueAt > end }
            if (language == "hi-IN") "आपके " + pending.size + " पेंडिंग काम हैं। आज " + todayCount + ", आने वाले " + upcomingCount + ", ओवरड्यू " + overdueCount + ", और हाई प्राथमिकता वाले " + highCount + " हैं।"
            else "You have " + pending.size + " pending tasks: " + todayCount + " due today, " + upcomingCount + " upcoming, " + overdueCount + " overdue and " + highCount + " high priority."
        } else if (selected.isEmpty()) {
            if (language == "hi-IN") "इस श्रेणी में कोई रिमाइंडर नहीं मिला।" else "There are no reminders in this category."
        } else if (parsed.first == ReminderQuery.DETAIL && selected.size > 1) {
            if (language == "hi-IN") "एक से अधिक रिमाइंडर मिल रहे हैं। कृपया नीचे सही रिमाइंडर चुनें।"
            else "I found more than one matching reminder. Please select the correct one below."
        } else {
            val prefix = if (language == "hi-IN") "मुझे " + selected.size + " रिमाइंडर मिले। " else "I found " + selected.size + " reminders. "
            prefix + selected.take(8).joinToString(". ") { item ->
                val date = SimpleDateFormat("EEE, dd MMM 'at' hh:mm a", Locale.getDefault()).format(Date(item.dueAt))
                item.title + ", " + item.priority + " priority, due " + date
            } + if (selected.size > 8) ". And " + (selected.size - 8) + " more." else "."
        }
        result = QueryResult(heading, answer, selected)
        status = "Answer ready."
    }

    fun speakAnswer() {
        val text = result?.answer ?: return
        if (ttsReady) {
            tts?.language = if (language == "hi-IN") Locale("hi", "IN") else Locale("en", "IN")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "remindermate-query-answer")
            status = "Reading the answer aloud."
        } else status = "Text-to-Speech is not ready. The answer is available on screen."
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Ask ReminderMate") },
            navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your reminders, answered", style = MaterialTheme.typography.headlineSmall)
            Text("Ask in English or Hindi. This assistant only reads reminders; it never changes them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "en-IN", onClick = { language = "en-IN" }, label = { Text("English") })
                FilterChip(selected = language == "hi-IN", onClick = { language = "hi-IN" }, label = { Text("हिंदी") })
            }
            Button(enabled = !listening, onClick = {
                listening = true
                activity.listen(language, { spoken -> listening = false; runQuery(spoken) }, { error -> listening = false; status = error })
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (listening) "Listening…" else "Ask by voice")
            }
            OutlinedTextField(value = question, onValueChange = { question = it },
                label = { Text("Or type your question") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedButton(onClick = { runQuery(question) }, enabled = question.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Get answer")
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.let { answer ->
                HorizontalDivider()
                Text(answer.heading, style = MaterialTheme.typography.titleLarge)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(answer.answer, style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { speakAnswer() }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text("Read answer aloud")
                        }
                    }
                }
                if (answer.reminders.isNotEmpty()) {
                    Text("Matching reminders", style = MaterialTheme.typography.titleMedium)
                    LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(answer.reminders, key = { it.id }) { item ->
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                                    Text(item.category + " • " + item.priority + " priority")
                                    Text(SimpleDateFormat("EEE, dd MMM yyyy • hh:mm a", Locale.getDefault()).format(Date(item.dueAt)), style = MaterialTheme.typography.bodySmall)
                                    if (item.notes.isNotBlank()) Text(item.notes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (result == null) {
                Text("Try:\n• What are my tasks today?\n• Show upcoming reminders\n• Which tasks are overdue?\n• How many high-priority tasks?\n• Show my pending tasks\n• What is my dentist appointment?\n\n• आज मेरे कौन से काम हैं?\n• आने वाले रिमाइंडर दिखाओ\n• कौन से काम ओवरड्यू हैं?\n• हाई प्राथमिकता के कितने काम हैं?",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private fun engineLanguage(engine: TextToSpeech?, language: String) {
    engine?.language = if (language == "hi-IN") Locale("hi", "IN") else Locale("en", "IN")
}

private fun classifyReminderQuery(raw: String): Pair<ReminderQuery, List<String>>? {
    val text = raw.lowercase(Locale.ROOT).trim()
    val tokens = text.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
    val stop = setOf("what", "is", "are", "my", "the", "about", "when", "tell", "me", "show", "find", "details",
        "detail", "reminder", "reminders", "task", "tasks", "please", "kya", "hai", "mere", "meri", "ka", "ki",
        "ke", "batao", "dikhao", "kab", "mera", "mujhe", "wala", "wali", "waale")
    val detailTerms = tokens.filterNot { it in stop }
    val query = when {
        listOf("high priority", "priority", "उच्च प्राथमिकता", "हाई प्राथमिकता", "प्राथमिकता").any { text.contains(it) } -> ReminderQuery.HIGH_PRIORITY
        listOf("summary", "overview", "how many", "कितने", "कुल", "सारांश").any { text.contains(it) } -> ReminderQuery.SUMMARY
        listOf("overdue", "late", "missed", "ओवरड्यू", "बकाया", "छूटे").any { text.contains(it) } -> ReminderQuery.OVERDUE
        listOf("upcoming", "next", "आने वाले", "आगामी", "अगले").any { text.contains(it) } -> ReminderQuery.UPCOMING
        listOf("today", "आज", "आज के", "आज मेरे").any { text.contains(it) } -> ReminderQuery.TODAY
        listOf("pending", "all tasks", "all reminders", "पेंडिंग", "लंबित", "सभी काम", "सारे काम").any { text.contains(it) } -> ReminderQuery.PENDING
        detailTerms.isNotEmpty() -> ReminderQuery.DETAIL
        else -> null
    }
    return query to if (query == ReminderQuery.DETAIL) detailTerms else emptyList()
}
