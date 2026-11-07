package com.jagdeep.autoclicker

import com.jagdeep.shared.live.LiveSource
import com.jagdeep.shared.live.log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryLiveLogTest {
    private var time = 1_000L
    private val log = MemoryLiveLog(now = { time++ }, capacity = 3)

    @Test
    fun `given developer mode off when something happens then nothing is kept and the text isn't even built`() {
        var built = false
        log.log(LiveSource.App) { built = true; "hidden" }
        assertTrue(log.entries.value.isEmpty())
        assertEquals(false, built)
    }

    @Test
    fun `given recording when more than fits arrives then only the newest are kept, in order, with rising ids`() {
        log.recording = true
        listOf("a", "b", "c", "d").forEach { log.add(LiveSource.Chrome, it) }
        assertEquals(listOf("b", "c", "d"), log.entries.value.map { it.text })
        assertEquals(listOf(2L, 3L, 4L), log.entries.value.map { it.id })
        assertTrue(log.entries.value.zipWithNext().all { (first, second) -> first.atMillis < second.atMillis })
    }

    @Test
    fun `given entries when cleared then the log is empty`() {
        log.recording = true
        log.add(LiveSource.App, "x")
        log.clear()
        assertTrue(log.entries.value.isEmpty())
    }
}
