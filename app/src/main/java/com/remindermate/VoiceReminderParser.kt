package com.remindermate

import java.util.Calendar
import java.util.Locale

data class ParsedVoiceReminder(
    val title: String,
    val notes: String,
    val category: String,
    val priority: String,
    val dueAt: Long?,
    val notificationLeadMinutes: Int,
    val repeat: String,
    val timeNeedsConfirmation: Boolean
)

/**
 * Offline, deterministic extraction from one speech-recognition transcript.
 * Ambiguous values are surfaced for review; nothing is saved by this parser.
 */
object VoiceReminderParser {
    fun parse(rawText: String, language: String, now: Long = System.currentTimeMillis()): ParsedVoiceReminder {
        val text = normalizeDigits(rawText.trim())
        val lower = text.lowercase(Locale.ROOT)
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val dateFound = applyDate(calendar, lower, now)
        val time = parseTime(text, lower)
        var timeNeedsConfirmation = time?.ambiguous == true

        if (time != null) {
            calendar.set(Calendar.HOUR_OF_DAY, time.hour)
            calendar.set(Calendar.MINUTE, time.minute)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        } else {
            calendar.set(Calendar.HOUR_OF_DAY, 9)
            calendar.set(Calendar.MINUTE, 0)
            calendar.set(Calendar.SECOND, 0)
            calendar.set(Calendar.MILLISECOND, 0)
        }

        // If only a time was spoken, use the next occurrence of that clock time.
        if (!dateFound && time != null && calendar.timeInMillis <= now) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }
        val dueAt = if (time != null) calendar.timeInMillis else null

        val lead = Regex("""(?i)(?:notify me|remind me|alert me)?\s*(\d{1,4})\s*(minutes?|mins?|minutes? before|मिनट)\s*(?:before|पहले)?""")
            .find(lower)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 10080) ?: 0

        val priority = when {
            listOf("high priority", "urgent", "उच्च प्राथमिकता", "ज्यादा जरूरी", "बहुत जरूरी").any { lower.contains(it) } -> "High"
            listOf("low priority", "कम प्राथमिकता", "कम जरूरी").any { lower.contains(it) } -> "Low"
            listOf("medium priority", "मध्यम प्राथमिकता").any { lower.contains(it) } -> "Medium"
            else -> "Medium"
        }
        val category = when {
            listOf("professional", "work", "office", "काम", "ऑफिस", "प्रोफेशनल").any { lower.contains(it) } -> "Professional"
            else -> "Personal"
        }
        val repeat = when {
            listOf("every day", "daily", "रोज", "हर दिन", "प्रतिदिन").any { lower.contains(it) } -> "Daily"
            listOf("every week", "weekly", "हर हफ्ते", "साप्ताहिक").any { lower.contains(it) } -> "Weekly"
            listOf("every month", "monthly", "हर महीने", "मासिक").any { lower.contains(it) } -> "Monthly"
            else -> "Never"
        }

        val noteMatch = Regex("""(?i)(?:notes?|नोट्स?)\s*[:,-]?\s*(.+)$""").find(text)
        val notes = noteMatch?.groupValues?.get(1)?.trim().orEmpty()
        var title = text
        if (noteMatch != null) title = title.removeRange(noteMatch.range)
        title = title
            .replace(Regex("""(?i)please\s+remind\s+me\s+to|remind\s+me\s+to|remind\s+me|please\s+remind\s+me|create\s+(?:a\s+)?reminder\s+to|add\s+(?:a\s+)?(?:work\s+)?task\s+to"""), " ")
            .replace(Regex("""(?i)day after tomorrow|tomorrow|today|next\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|\b(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b"""), " ")
            .replace(Regex("""(?i)\b(?:at\s*)?\d{1,2}(?::|\s)\d{2}\s*(?:a\.?m\.?|p\.?m\.?)?\b|\b\d{1,2}\s*(?:a\.?m\.?|p\.?m\.?)\b|\b\d{1,2}\s*o'?clock\b|\b\d{1,2}\s*बजे\b"""), " ")
            .replace(Regex("""(?i)\b(?:high|medium|low)\s+priority\b|\burgent\b|\b(?:personal|professional|work|office)\b|\b(?:notify|alert)\s+me\b|\b\d{1,4}\s*(?:minutes?|mins?)\s*(?:before)?\b|\b(?:every\s+day|daily|every\s+week|weekly|every\s+month|monthly)\b"""), " ")
            .replace(Regex("""कल|परसों|आज|अगले दिन|सुबह|प्रातः|दोपहर|शाम|रात|हर दिन|रोज|प्रतिदिन|हर हफ्ते|साप्ताहिक|हर महीने|मासिक|उच्च प्राथमिकता|मध्यम प्राथमिकता|कम प्राथमिकता|प्रोफेशनल|ऑफिस|काम|व्यक्तिगत|\d+\s*मिनट\s*पहले|\d{1,2}(?::\d{2})?\s*बजे"""), " ")
            .replace(Regex("""(?i)\b(?:on|by|at|in|for|and|with|category|priority|notify|before)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '.', '।', '-', ':')
        if (title.isBlank()) title = text.trim()

        return ParsedVoiceReminder(
            title = title,
            notes = notes,
            category = category,
            priority = priority,
            dueAt = dueAt,
            notificationLeadMinutes = lead,
            repeat = repeat,
            timeNeedsConfirmation = timeNeedsConfirmation
        )
    }

    private data class ParsedTime(val hour: Int, val minute: Int, val ambiguous: Boolean)

    private fun parseTime(text: String, lower: String): ParsedTime? {
        val ampm = Regex("""(?i)\b(?:at\s*)?(\d{1,2})(?::|\s)?(\d{2})?\s*(a\.?m\.?|p\.?m\.?)\b""").find(text)
        if (ampm != null) {
            var hour = ampm.groupValues[1].toIntOrNull() ?: return null
            val minute = ampm.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 1..12 || minute !in 0..59) return null
            if (ampm.groupValues[3].startsWith("p", true) && hour < 12) hour += 12
            if (ampm.groupValues[3].startsWith("a", true) && hour == 12) hour = 0
            return ParsedTime(hour, minute, false)
        }

        val hindiPrefix = Regex("""(सुबह|प्रातः|दोपहर|शाम|रात)\s*(\d{1,2})(?::(\d{2}))?\s*(?:बजे)?""").find(text)
        val hindiSuffix = Regex("""(\d{1,2})(?::(\d{2}))?\s*बजे\s*(सुबह|प्रातः|दोपहर|शाम|रात)?""").find(text)
        val explicit = hindiPrefix ?: hindiSuffix
        if (explicit != null) {
            val isPrefix = hindiPrefix != null
            var hour = explicit.groupValues[if (isPrefix) 2 else 1].toIntOrNull() ?: return null
            val minute = explicit.groupValues[if (isPrefix) 3 else 2].toIntOrNull() ?: 0
            val period = explicit.groupValues[if (isPrefix) 1 else 3]
            if (hour !in 0..23 || minute !in 0..59) return null
            if (period in listOf("दोपहर", "शाम", "रात") && hour in 1..11) hour += 12
            if (period in listOf("सुबह", "प्रातः") && hour == 12) hour = 0
            return ParsedTime(hour, minute, false)
        }

        val clock = Regex("""(?i)\b(?:at\s+)(\d{1,2})(?::|\s)(\d{2})\b|\b(\d{1,2}):(\d{2})\b""").find(text)
        if (clock != null) {
            val hour = (clock.groupValues[1].ifBlank { clock.groupValues[3] }).toIntOrNull() ?: return null
            val minute = (clock.groupValues[2].ifBlank { clock.groupValues[4] }).toIntOrNull() ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            val evening = listOf("evening", "tonight", "afternoon", "रात", "शाम", "दोपहर").any { lower.contains(it) }
            val morning = listOf("morning", "सुबह").any { lower.contains(it) }
            val resolvedHour = when {
                hour > 12 -> hour
                evening && hour in 1..11 -> hour + 12
                morning && hour == 12 -> 0
                else -> hour
            }
            return ParsedTime(resolvedHour, minute, hour in 1..12 && !evening && !morning)
        }

        val hourOnly = Regex("""(?i)\b(?:at\s+)(\d{1,2})\s*(?:o'?clock)?\b""").find(text)
        if (hourOnly != null) {
            val hour = hourOnly.groupValues[1].toIntOrNull() ?: return null
            if (hour !in 0..23) return null
            val evening = listOf("evening", "tonight", "afternoon", "रात", "शाम", "दोपहर").any { lower.contains(it) }
            val morning = listOf("morning", "सुबह").any { lower.contains(it) }
            val resolved = when {
                evening && hour in 1..11 -> hour + 12
                morning && hour == 12 -> 0
                else -> hour
            }
            return ParsedTime(resolved, 0, hour in 1..12 && !evening && !morning)
        }
        return null
    }

    private fun applyDate(calendar: Calendar, lower: String, now: Long): Boolean {
        when {
            lower.contains("day after tomorrow") || lower.contains("परसों") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 2); return true
            }
            lower.contains("tomorrow") || lower.contains("कल") -> {
                calendar.add(Calendar.DAY_OF_YEAR, 1); return true
            }
            lower.contains("today") || lower.contains("आज") -> return true
        }

        val date = Regex("""\b(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""").find(lower)
        if (date != null) {
            val day = date.groupValues[1].toIntOrNull() ?: return false
            val month = date.groupValues[2].toIntOrNull()?.minus(1) ?: return false
            var year = date.groupValues[3].toIntOrNull() ?: calendar.get(Calendar.YEAR)
            if (year < 100) year += 2000
            if (month !in 0..11 || day !in 1..31) return false
            calendar.set(year, month, day)
            return true
        }

        val monthNames = mapOf(
            "jan" to 0, "january" to 0, "feb" to 1, "february" to 1, "mar" to 2, "march" to 2,
            "apr" to 3, "april" to 3, "may" to 4, "jun" to 5, "june" to 5, "jul" to 6, "july" to 6,
            "aug" to 7, "august" to 7, "sep" to 8, "september" to 8, "oct" to 9, "october" to 9,
            "nov" to 10, "november" to 10, "dec" to 11, "december" to 11
        )
        val monthPattern = monthNames.keys.joinToString("|")
        val monthMatch = Regex("""(?i)\b(\d{1,2})(?:st|nd|rd|th)?\s+($monthPattern)(?:\s+(\d{4}))?\b|\b($monthPattern)\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""").find(lower)
        if (monthMatch != null) {
            val day = (monthMatch.groupValues[1].ifBlank { monthMatch.groupValues[5] }).toIntOrNull() ?: return false
            val monthName = monthMatch.groupValues[2].ifBlank { monthMatch.groupValues[4] }
            val yearText = monthMatch.groupValues[3].ifBlank { monthMatch.groupValues[6] }
            val year = yearText.toIntOrNull() ?: calendar.get(Calendar.YEAR)
            val month = monthNames[monthName.lowercase(Locale.ROOT)] ?: return false
            if (day !in 1..31) return false
            calendar.set(year, month, day)
            return true
        }

        val weekdays = mapOf(
            "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY, "wednesday" to Calendar.WEDNESDAY,
            "thursday" to Calendar.THURSDAY, "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY
        )
        val weekdayMatch = Regex("""(?i)\b(?:next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""").find(lower)
        if (weekdayMatch != null) {
            val target = weekdays[weekdayMatch.groupValues[1].lowercase(Locale.ROOT)] ?: return false
            var days = (target - calendar.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (days == 0) days = 7
            if (lower.contains("next ")) days = if (days < 7) days + 7 else days
            calendar.add(Calendar.DAY_OF_YEAR, days)
            return true
        }
        return false
    }

    private fun normalizeDigits(value: String): String {
        val hindi = "०१२३४५६७८९"
        return buildString {
            value.forEach { ch ->
                val index = hindi.indexOf(ch)
                append(if (index >= 0) ('0'.code + index).toChar() else ch)
            }
        }
    }
}
