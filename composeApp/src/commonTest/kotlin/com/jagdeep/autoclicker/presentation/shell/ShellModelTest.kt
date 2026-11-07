package com.jagdeep.autoclicker.presentation.shell

import com.jagdeep.automation.UnsupportedDesktopAutomation
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.PageActivity
import com.jagdeep.home.domain.PageHelperRoutine
import com.jagdeep.home.domain.PageRunner
import com.jagdeep.home.domain.PageState
import com.jagdeep.home.domain.Routine
import com.jagdeep.home.domain.RoutineLibrary
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ShellModelTest {
    private val click = ClickRoutine("c", "Refresh")
    private val helper = PageHelperRoutine("h", "Course")

    private class Repository(library: RoutineLibrary) : RoutineRepository {
        override val library = MutableStateFlow<RoutineLibrary?>(library)
        override suspend fun save(routine: Routine) = Unit
        override suspend fun delete(id: String) = Unit
        override suspend fun select(id: String) {
            library.value = library.value?.copy(selectedId = id)
        }
    }

    private class Page : PageRunner {
        override val state = MutableStateFlow(PageState(isConnected = true))
        override val lastRun = MutableStateFlow<RunRecord?>(null)
        override fun start(routine: Routine): String? {
            if (routine is PageHelperRoutine) state.value = state.value.copy(activity = PageActivity.Helper(routine))
            return null
        }
        override fun stop(): Boolean {
            val active = isActive
            state.value = state.value.copy(activity = null)
            return active
        }
        override fun toggleScrolling() = Unit
    }

    private val history = object : RunHistoryStore {
        override val records = MutableStateFlow<List<RunRecord>>(emptyList())
        override suspend fun add(record: RunRecord) = Unit
        override suspend fun clear() = Unit
    }

    private fun TestScope.model(repository: Repository, page: Page = Page()): ShellModel {
        val controller = ClickController(
            UnsupportedDesktopAutomation, repository, MutableStateFlow(AppPreferences()), history, backgroundScope, { 0L }, { "run" }, page = page,
        )
        return ShellModel(controller, repository).also { runCurrent() }
    }

    @Test
    fun `given routines when shown then the toolbar lists them with the selected one and Chrome's link`() = runTest {
        val state = model(Repository(RoutineLibrary(listOf(click, helper), "h"))).state.first()
        assertEquals(listOf<Routine>(click, helper), state.routines)
        assertEquals(helper, state.selected)
        assertTrue(state.chromeConnected)
        assertFalse(state.isActive)
    }

    @Test
    fun `given another routine picked when chosen then it becomes the selected one`() = runTest {
        val repository = Repository(RoutineLibrary(listOf(click, helper), "c"))
        val model = model(repository)
        model.select(helper)
        assertEquals(helper, model.state.first().selected)
    }

    @Test
    fun `given the selected routine when started and stopped from the toolbar then it runs and stops`() = runTest {
        val repository = Repository(RoutineLibrary(listOf(click, helper), "h"))
        val model = model(repository)
        model.start()
        runCurrent()
        val running = model.state.first()
        assertTrue(running.isActive)
        assertEquals("Course", running.runningName)

        model.stop()
        runCurrent()
        assertFalse(model.state.first().isActive)
    }

    @Test
    fun `given what's happening when shown then the status line says it in a few words`() = runTest {
        val page = Page()
        val model = model(Repository(RoutineLibrary(listOf(click, helper), "h")), page)
        assertEquals("Ready · Course", model.state.first().operation)
        model.start()
        runCurrent()
        assertEquals("Course · looking for a button", model.state.first().operation)
        page.state.value = page.state.value.copy(foundLabel = "Next", isScrolling = true)
        assertEquals("Course · next step: Next · scrolling", model.state.first().operation)
    }

    @Test
    fun `given a next click time when counted down then it reads in tenths, then minutes`() {
        assertEquals("next click in 1.5 s", nextClickLabel(2_500, 1_000))
        assertEquals("next click in 0.1 s", nextClickLabel(1_001, 1_000))
        assertEquals("next click in 0.0 s", nextClickLabel(900, 1_000))
        assertEquals("next click in 2 min 5 s", nextClickLabel(126_000, 1_000))
    }
}
