package com.remindermate

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class VoiceReminderParserTest {
    private fun fixedNow(): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, 26, 10, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test fun parsesCompleteEnglishUtterance() {
        val result = VoiceReminderParser.parse(
            "Remind me to submit CMS deployment report tomorrow at 4:30 PM, high priority, professional, notify me 15 minutes before, daily",
            "en-IN", fixedNow()
        )
        val due = Calendar.getInstance().apply { timeInMillis = result.dueAt!! }
        assertEquals("CMS deployment report", result.title)
        assertEquals("Professional", result.category)
        assertEquals("High", result.priority)
        assertEquals("Daily", result.repeat)
        assertEquals(15, result.notificationLeadMinutes)
        assertEquals(Calendar.NOVEMBER, due.get(Calendar.MONTH))
        assertEquals(27, due.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, due.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, due.get(Calendar.MINUTE))
        assertFalse(result.timeNeedsConfirmation)
    }

    @Test fun parsesHindiUtteranceAndFlagsMissingAmPm() {
        val result = VoiceReminderParser.parse(
            "मुझे कल शाम 4 बजे बैंक रिपोर्ट याद दिलाना, उच्च प्राथमिकता, ऑफिस, 10 मिनट पहले",
            "hi-IN", fixedNow()
        )
        val due = Calendar.getInstance().apply { timeInMillis = result.dueAt!! }
        assertTrue(result.title.contains("बैंक रिपोर्ट"))
        assertEquals("Professional", result.category)
        assertEquals("High", result.priority)
        assertEquals(10, result.notificationLeadMinutes)
        assertEquals(27, due.get(Calendar.DAY_OF_MONTH))
        assertEquals(16, due.get(Calendar.HOUR_OF_DAY))
        assertFalse(result.timeNeedsConfirmation)
    }

    @Test fun dateWithoutTimeIsNotSilentlyInvented() {
        val result = VoiceReminderParser.parse("Remind me to call the bank tomorrow", "en-IN", fixedNow())
        assertNull(result.dueAt)
        assertNotNull(result.dateAtStartOfDay)
    }

    @Test fun ambiguousClockTimeRequiresReview() {
        val result = VoiceReminderParser.parse("Submit report tomorrow at 4:30", "en-IN", fixedNow())
        assertNotNull(result.dueAt)
        assertTrue(result.timeNeedsConfirmation)
    }
}
