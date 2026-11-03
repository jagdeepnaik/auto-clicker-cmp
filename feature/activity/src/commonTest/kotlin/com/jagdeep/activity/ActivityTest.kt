package com.jagdeep.activity

import com.jagdeep.activity.data.PreferencesRunHistoryStore
import com.jagdeep.activity.domain.ActivityViewModel
import com.jagdeep.activity.ui.util.durationLabel
import com.jagdeep.activity.ui.util.runTimeLabel
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityTest {
    private fun record(id: String) = RunRecord(id, "Routine", 0, 1_000, 5, RunOutcome.Completed)

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
            advanceUntilIdle()
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `given runs when added then the newest comes first and they survive a new instance`() = runTest {
        val backing = InMemoryPreferencesStore("")
        PreferencesRunHistoryStore(backing).run {
            add(record("old"))
            add(record("new"))
        }
        assertEquals(listOf("new", "old"), PreferencesRunHistoryStore(backing).records.first().map { it.id })
    }

    @Test
    fun `given the cap when another run is added then the oldest is dropped`() = runTest {
        val store = PreferencesRunHistoryStore(InMemoryPreferencesStore(""), maxRecords = 2)
        listOf("a", "b", "c").forEach { store.add(record(it)) }
        assertEquals(listOf("c", "b"), store.records.first().map { it.id })
    }

    @Test
    fun `given damaged saved history when read then it starts empty instead of failing`() = runTest {
        assertTrue(PreferencesRunHistoryStore(InMemoryPreferencesStore("not json")).records.first().isEmpty())
    }

    @Test
    fun `given no runs when shown then loading ends with an empty list`() = test {
        val viewModel = ActivityViewModel(PreferencesRunHistoryStore(InMemoryPreferencesStore("")), now = { 0 })
        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.records.isEmpty())
    }

    @Test
    fun `given runs when clearing is cancelled they stay, and when confirmed they go`() = test {
        val store = PreferencesRunHistoryStore(InMemoryPreferencesStore(""))
        store.add(record("a"))
        val viewModel = ActivityViewModel(store, now = { 0 })
        advanceUntilIdle()

        viewModel.requestClear()
        assertTrue(viewModel.state.value.isConfirmingClear)
        viewModel.cancelClear()
        advanceUntilIdle()
        assertEquals(1, viewModel.state.value.records.size)

        viewModel.requestClear()
        viewModel.confirmClear()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.isConfirmingClear)
        assertTrue(viewModel.state.value.records.isEmpty())
    }

    @Test
    fun `given durations when labelled then the units suit the length`() {
        assertEquals("under 1 s", durationLabel(400))
        assertEquals("45 s", durationLabel(45_000))
        assertEquals("3 min 20 s", durationLabel(200_000))
        assertEquals("2 min", durationLabel(120_000))
        assertEquals("2 h 5 min", durationLabel(7_500_000))
        assertEquals("1 h", durationLabel(3_600_000))
    }

    @Test
    fun `given times when labelled then nearby days read naturally`() {
        val day = 86_400_000L
        val now = 1_760_000_000_000L
        val utc = TimeZone.UTC
        assertTrue(runTimeLabel(now, now, utc).startsWith("Today, "))
        assertTrue(runTimeLabel(now - day, now, utc).startsWith("Yesterday, "))
        assertTrue(runTimeLabel(now - 400 * day, now, utc).contains("2024"))
    }
}
