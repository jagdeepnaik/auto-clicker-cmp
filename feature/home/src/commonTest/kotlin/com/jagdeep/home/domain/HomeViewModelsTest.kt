package com.jagdeep.home.domain

import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.automation.HotKey
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelsTest {
    private val automation = FakeAutomation()
    private val first = ClickRoutine("a", "First")
    private val second = ClickRoutine("b", "Second")

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.controller(repository: RoutineRepository) = ClickController(
        automation, repository, MutableStateFlow(AppPreferences()), FakeRunHistory(), backgroundScope, { currentTime }, { "run" },
    )

    @Test
    fun `given routines not yet read when shown then it is loading, and then shows the selection`() = test {
        val loading = HomeViewModel(controller(FakeRoutineRepository(null)), FakeRoutineRepository(null))
        runCurrent()
        assertTrue(loading.state.value.isLoading)

        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first, second), "b"))
        val viewModel = HomeViewModel(controller(repository), repository)
        runCurrent()
        assertFalse(viewModel.state.value.isLoading)
        assertEquals(second, viewModel.state.value.selected)
    }

    @Test
    fun `given a selection that no longer exists when shown then the first routine is used`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first, second), "gone"))
        val viewModel = HomeViewModel(controller(repository), repository)
        runCurrent()
        assertEquals(first, viewModel.state.value.selected)
    }

    @Test
    fun `given a selected routine when start and stop are pressed then the state follows the run`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first), "a"))
        val viewModel = HomeViewModel(controller(repository), repository)
        runCurrent()

        viewModel.start()
        runCurrent()
        assertIs<RunState.CountingDown>(viewModel.state.value.run)

        viewModel.stop()
        runCurrent()
        assertEquals(RunState.Idle, viewModel.state.value.run)
    }

    @Test
    fun `given a deletion request when cancelled it stays, and when confirmed it goes`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first, second), "a"))
        val viewModel = HomeViewModel(controller(repository), repository)
        runCurrent()

        viewModel.requestDeletion(second)
        viewModel.cancelDeletion()
        runCurrent()
        assertNull(viewModel.state.value.routinePendingDeletion)
        assertEquals(2, viewModel.state.value.routines.size)

        viewModel.requestDeletion(second)
        viewModel.confirmDeletion()
        runCurrent()
        assertEquals(listOf(first), viewModel.state.value.routines)
    }

    @Test
    fun `given missing access when the user allows it in System Settings then the state notices`() = test {
        automation.inputAccess = InputAccess.NeedsPermission
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first), "a"))
        val viewModel = HomeViewModel(controller(repository), repository)
        runCurrent()
        assertEquals(InputAccess.NeedsPermission, viewModel.state.value.access)

        viewModel.requestAccess()
        assertEquals(1, automation.accessRequests)

        automation.inputAccess = InputAccess.Granted
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(InputAccess.Granted, viewModel.state.value.access)
    }

    private fun TestScope.editor(repository: FakeRoutineRepository, id: String? = null) =
        RoutineEditorViewModel(id, controller(repository), repository, newId = { "new-id" })

    @Test
    fun `given a new routine when opened then it has a suggested name and lists running apps`() = test {
        val viewModel = editor(FakeRoutineRepository(RoutineLibrary(listOf(first))))
        runCurrent()
        assertTrue(viewModel.state.value.isNew)
        assertEquals("Routine 2", viewModel.state.value.draft.name)
        assertEquals(listOf("Finder", "Google Chrome"), viewModel.state.value.runningApps)
    }

    @Test
    fun `given an existing routine when opened and saved then it keeps its id`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(first)))
        val viewModel = editor(repository, "a")
        runCurrent()
        assertEquals("First", viewModel.state.value.draft.name)

        viewModel.updateDraft { it.copy(name = "Renamed") }
        viewModel.save()
        runCurrent()
        assertTrue(viewModel.state.value.isSaved)
        assertEquals(listOf("Renamed"), repository.library.value!!.routines.map { it.name })
        assertEquals("a", repository.library.value!!.routines.single().id)
    }

    @Test
    fun `given invalid values when saved then errors show, nothing is saved, and editing clears them`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary())
        val viewModel = editor(repository)
        runCurrent()
        viewModel.updateDraft { it.copy(name = "") }
        viewModel.save()
        runCurrent()
        assertNotNull(viewModel.state.value.errors.name)
        assertFalse(viewModel.state.value.isSaved)
        assertTrue(repository.library.value!!.routines.isEmpty())

        viewModel.updateDraft { it.copy(name = "Fixed") }
        assertNull(viewModel.state.value.errors.name)
    }

    @Test
    fun `given capture mode when the hot key is pressed twice then two points are added and it stays on`() = test {
        val viewModel = editor(FakeRoutineRepository(RoutineLibrary()))
        runCurrent()
        viewModel.startCapture()
        runCurrent()
        automation.position = ScreenPoint(10, 20)
        automation.keyPresses.emit(HotKey.F7)
        runCurrent()
        automation.position = ScreenPoint(30, 40)
        automation.keyPresses.emit(HotKey.F7)
        runCurrent()
        assertEquals(listOf(PointDraft("10", "20"), PointDraft("30", "40")), viewModel.state.value.draft.points)
        assertEquals(2, viewModel.state.value.capture?.added)

        advanceTimeBy(101)
        assertEquals(ScreenPoint(30, 40), viewModel.state.value.capture?.pointer)

        viewModel.finishCapture()
        runCurrent()
        assertNull(viewModel.state.value.capture)
        automation.keyPresses.emit(HotKey.F7)
        runCurrent()
        assertEquals(2, viewModel.state.value.draft.points.size)
    }

    @Test
    fun `given points when one is edited or removed then only that one changes`() = test {
        val viewModel = editor(FakeRoutineRepository(RoutineLibrary()))
        runCurrent()
        viewModel.addEmptyPoint()
        viewModel.addEmptyPoint()
        viewModel.updatePoint(1, PointDraft("5", "6"))
        assertEquals(listOf(PointDraft(), PointDraft("5", "6")), viewModel.state.value.draft.points)
        viewModel.removePoint(0)
        assertEquals(listOf(PointDraft("5", "6")), viewModel.state.value.draft.points)
    }
}
