package com.remindermate.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val category: String = "Personal",
    val priority: String = "Medium",
    val dueAt: Long,
    val repeat: String = "Never",
    val completed: Boolean = false,
    val snoozedUntil: Long = 0L,
    val notificationLeadMinutes: Int = 0
)
