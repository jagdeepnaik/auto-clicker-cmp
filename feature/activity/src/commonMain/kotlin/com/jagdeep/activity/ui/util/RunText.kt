package com.jagdeep.activity.ui.util

import kotlin.time.Instant
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

/**
 * When a run started, relative to [nowMillis]: "Today, 2:05 PM", "Yesterday, 9:12 AM",
 * "12 Oct, 4:30 PM", or "12 Oct 2025, 4:30 PM" for an earlier year.
 */
internal fun runTimeLabel(millis: Long, nowMillis: Long, timeZone: TimeZone): String {
    val at = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    val today = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(timeZone).date
    val day = when {
        at.date == today -> "Today"
        at.date == today.minus(DatePeriod(days = 1)) -> "Yesterday"
        at.date.year == today.year -> at.date.format(DAY_MONTH)
        else -> at.date.format(DAY_MONTH_YEAR)
    }
    return "$day, ${at.format(TIME)}"
}

/** "45 s", "3 min 20 s", "2 h 5 min". Under a second reads as "under 1 s". */
internal fun durationLabel(millis: Long): String {
    val seconds = millis / 1_000
    return when {
        seconds < 1 -> "under 1 s"
        seconds < 60 -> "$seconds s"
        seconds < 3_600 -> if (seconds % 60 == 0L) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"
        else -> if ((seconds % 3_600) / 60 == 0L) "${seconds / 3_600} h" else "${seconds / 3_600} h ${(seconds % 3_600) / 60} min"
    }
}

internal fun clickCountLabel(clicks: Long): String = if (clicks == 1L) "1 click" else "$clicks clicks"

private val DAY_MONTH = LocalDate.Format {
    day(padding = Padding.NONE); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED)
}

private val DAY_MONTH_YEAR = LocalDate.Format {
    day(padding = Padding.NONE); char(' '); monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); year()
}

private val TIME = LocalDateTime.Format {
    amPmHour(padding = Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
}
