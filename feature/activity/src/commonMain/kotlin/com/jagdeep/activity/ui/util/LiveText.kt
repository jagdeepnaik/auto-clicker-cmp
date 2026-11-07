package com.jagdeep.activity.ui.util

import com.jagdeep.shared.live.LiveEntry
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** "14:05:09.042": precise enough to see how fast messages follow each other. */
internal fun liveTimeLabel(millis: Long, timeZone: TimeZone): String {
    val time = Instant.fromEpochMilliseconds(millis).toLocalDateTime(timeZone)
    fun two(value: Int) = value.toString().padStart(2, '0')
    return "${two(time.hour)}:${two(time.minute)}:${two(time.second)}.${(millis % 1_000).toString().padStart(3, '0')}"
}

/** The whole log as plain text, for pasting into a bug report. */
internal fun liveLogText(entries: List<LiveEntry>, timeZone: TimeZone): String =
    entries.joinToString("\n") { "${liveTimeLabel(it.atMillis, timeZone)}  ${it.source.label.padEnd(7)}  ${it.text}" }
