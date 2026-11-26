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
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class NotificationVoiceAction { DONE, SNOOZE_10, SNOOZE_60 }

class VoiceNotificationActionActivity : ComponentActivity() {
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
            onError("Speech recognition is unavailable. Type your command instead.")
            return
        }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                recognizer.destroy()
                onError("I couldn't recognize that. Please try again or type the command.")
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                if (spoken.isNullOrBlank()) onError("No speech detected. Please try again.")
                else onResult(spoken)
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
        val reminderId = intent.getLongExtra("reminder_id", -1L)
        setContent { MaterialTheme(colorScheme = reminderMateColorScheme()) { NotificationVoiceScreen(this, reminderId) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotificationVoiceScreen(activity: VoiceNotificationActionActivity, reminderId: Long) {
    val context = LocalContext.current
    val dao = remember { ReminderDatabase.get(context).reminderDao() }
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf("en-IN") }
    var reminder by remember { mutableStateOf<Reminder?>(null) }
    var commandText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Say: Mark as done, Snooze for 10 minutes, or Remind me again in one hour.") }
    var proposed by remember { mutableStateOf<NotificationVoiceAction?>(null) }
    var showConfirmation by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var ttsReady by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val engine = TextToSpeech(context) { result ->
            ttsReady = result == TextToSpeech.SUCCESS
        }
        tts = engine
        onDispose { engine.stop(); engine.shutdown(); tts = null; ttsReady = false }
    }
    LaunchedEffect(reminderId) {
        reminder = withContext(Dispatchers.IO) { if (reminderId > 0) dao.getById(reminderId) else null }
        if (reminder == null) status = "This reminder is no longer available."
        else if (reminder?.completed == true) status = "This reminder is already completed."
    }

    fun parseCommand(raw: String) {
        commandText = raw
        val value = raw.lowercase(Locale.ROOT)
        proposed = when {
            listOf("mark as done", "mark done", "complete", "completed", "finish", "done", "पूरा", "हो गया", "काम खत्म").any { value.contains(it) } ->
                NotificationVoiceAction.DONE
            listOf("one hour", "1 hour", "60 minutes", "an hour", "एक घंटे", "एक घंटा", "घंटे बाद").any { value.contains(it) } ->
                NotificationVoiceAction.SNOOZE_60
            listOf("snooze", "10 minutes", "ten minutes", "दस मिनट", "10 मिनट", "थोड़ी देर").any { value.contains(it) } ->
                NotificationVoiceAction.SNOOZE_10
            else -> null
        }
        showConfirmation = proposed != null
        status = if (proposed == null) {
            if (language == "hi-IN") "कमांड समझ नहीं आया। कृपया दोबारा बोलें या टाइप करें।"
            else "I couldn't identify that action. Please try again or type a command."
        } else if (language == "hi-IN") "कृपया कार्रवाई की पुष्टि करें।"
        else "Please confirm before I change this reminder."
    }

    fun speak(text: String) {
        if (ttsReady) {
            tts?.language = if (language == "hi-IN") Locale("hi", "IN") else Locale("en", "IN")
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "remindermate-notification-action")
        }
    }

    fun confirmAction() {
        val item = reminder ?: return
        val action = proposed ?: return
        if (item.completed) { status = "This reminder is already completed."; showConfirmation = false; return }
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("reminder_id", item.id)
            when (action) {
                NotificationVoiceAction.DONE -> this.action = ReminderReceiver.ACTION_DONE
                NotificationVoiceAction.SNOOZE_10 -> {
                    this.action = ReminderReceiver.ACTION_SNOOZE
                    putExtra(ReminderReceiver.EXTRA_DELAY, 10 * 60 * 1000L)
                }
                NotificationVoiceAction.SNOOZE_60 -> {
                    this.action = ReminderReceiver.ACTION_SNOOZE
                    putExtra(ReminderReceiver.EXTRA_DELAY, 60 * 60 * 1000L)
                }
            }
        }
        context.sendBroadcast(intent)
        val message = when (action) {
            NotificationVoiceAction.DONE -> if (language == "hi-IN") "रिमाइंडर पूरा मार्क कर दिया गया।" else "Reminder marked as done."
            NotificationVoiceAction.SNOOZE_10 -> if (language == "hi-IN") "रिमाइंडर 10 मिनट के लिए स्नूज़ किया गया।" else "Reminder snoozed for 10 minutes."
            NotificationVoiceAction.SNOOZE_60 -> if (language == "hi-IN") "रिमाइंडर एक घंटे के लिए स्नूज़ किया गया।" else "Reminder snoozed for one hour."
        }
        status = message
        speak(message)
        showConfirmation = false
        activity.window.decorView.postDelayed({ activity.finish() }, 900)
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Voice reminder action") },
            navigationIcon = { IconButton(onClick = { activity.finish() }) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(reminder?.title ?: "Loading reminder…", style = MaterialTheme.typography.headlineSmall)
            Text("Voice actions require confirmation. No action is taken until you confirm.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = language == "en-IN", onClick = { language = "en-IN" }, label = { Text("English") })
                FilterChip(selected = language == "hi-IN", onClick = { language = "hi-IN" }, label = { Text("हिंदी") })
            }
            Button(enabled = reminder != null && reminder?.completed == false && !listening, onClick = {
                listening = true
                activity.listen(language, { listening = false; parseCommand(it) }, { listening = false; status = it })
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp))
                Text(if (listening) "Listening…" else "Speak action")
            }
            OutlinedTextField(commandText, { commandText = it }, label = { Text("Or type your action") },
                modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedButton(enabled = commandText.isNotBlank(), onClick = { parseCommand(commandText) },
                modifier = Modifier.fillMaxWidth()) { Text("Review command") }
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (showConfirmation) {
                val label = when (proposed) {
                    NotificationVoiceAction.DONE -> "Mark this reminder as done"
                    NotificationVoiceAction.SNOOZE_10 -> "Snooze this reminder for 10 minutes"
                    NotificationVoiceAction.SNOOZE_60 -> "Snooze this reminder for one hour"
                    null -> ""
                }
                AlertDialog(onDismissRequest = { showConfirmation = false },
                    title = { Text("Confirm action") },
                    text = { Text("$label?\n\nReminder: ${reminder?.title}") },
                    confirmButton = { TextButton(onClick = { confirmAction() }) { Text("Confirm") } },
                    dismissButton = { TextButton(onClick = { showConfirmation = false; proposed = null }) { Text("Cancel") } })
            }
        }
    }
}
