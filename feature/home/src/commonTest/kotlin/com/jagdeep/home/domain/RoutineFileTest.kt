package com.jagdeep.home.domain

import com.jagdeep.automation.PointerButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import com.jagdeep.shared.preferences.AppPreferences

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineFileTest {
    private val sequence = ClickRoutine(
        "seq", "Course steps",
        target = ClickTarget.AtPoints(listOf(ClickPoint(640, 880, 1_500, PointerButton.Right, "Play"), ClickPoint(10, 20, 250, PointerButton.Left, "Point 2"))),
        limit = RunLimit.Passes(1),
    )
    private val every = listOf(
        ClickRoutine("c", "Refresh", intervalMillis = 2_000),
        sequence,
        ScrollClickRoutine("s", "Next pages", ClickPoint(640, 880), rounds = 7),
        PageHelperRoutine("h", "Course", words = listOf("Weiter")),
    )

    @Test
    fun `given routines of every kind when exported and read back then each comes back the same`() {
        val text = RoutineFileCodec.encode(every)
        assertTrue("\"format\": \"autoclicker-routines\"" in text, "The file says what it is")
        val read = RoutineFileCodec.decode(text)!!
        assertEquals(every, read.routines)
        assertEquals(0, read.skipped)
    }

    @Test
    fun `given text that isn't a routine file when read then nothing is taken from it`() {
        assertNull(RoutineFileCodec.decode("not json"))
        assertNull(RoutineFileCodec.decode("""{"routines":[]}"""), "No format name")
        assertNull(RoutineFileCodec.decode("""{"format":"something-else","routines":[]}"""))
        assertNull(RoutineFileCodec.decode("x".repeat(RoutineFileCodec.MAX_FILE_CHARACTERS + 1)))
    }

    @Test
    fun `given a file with routines the editor would refuse when read then those are left out and counted`() {
        val text = """{"format":"autoclicker-routines","version":1,"routines":[
            {"type":"click","id":"a","name":"Too fast","intervalMillis":0},
            {"type":"pageHelper","id":"b","name":"No words","words":[]},
            {"type":"mystery","id":"c","name":"Unknown kind"},
            {"id":"d","name":"Saved before kinds","intervalMillis":500}
        ]}"""
        val read = RoutineFileCodec.decode(text)!!
        assertEquals(listOf("Saved before kinds"), read.routines.map { it.name })
        assertEquals(3, read.skipped)
    }

    private class FakeFiles(var toOpen: String? = null) : RoutineFiles {
        var saved: Pair<String, String>? = null
        override suspend fun save(suggestedName: String, contents: String): String {
            saved = suggestedName to contents
            return "/tmp/$suggestedName"
        }
        override suspend fun open(): String? = toOpen
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun TestScope.viewModel(repository: FakeRoutineRepository, files: FakeFiles): HomeViewModel {
        var id = 0
        val controller = ClickController(FakeAutomation(), repository, MutableStateFlow(AppPreferences()), FakeRunHistory(), backgroundScope, { currentTime }, { "run" })
        return HomeViewModel(controller, repository, files) { "new-${++id}" }
    }

    @Test
    fun `given routines when one is exported then the file holds just it, named after it`() = test {
        val files = FakeFiles()
        val viewModel = viewModel(FakeRoutineRepository(RoutineLibrary(every)), files)
        runCurrent()
        viewModel.export(sequence)
        runCurrent()
        assertEquals("Course steps.autoclicker.json", files.saved!!.first)
        assertEquals(listOf<Routine>(sequence), RoutineFileCodec.decode(files.saved!!.second)!!.routines)
        assertEquals("Exported 1 routine.", viewModel.messages.first())
    }

    @Test
    fun `given a routine file when imported then its routines join the user's own under new ids, and the first is selected`() = test {
        val own = ClickRoutine("mine", "Mine")
        val repository = FakeRoutineRepository(RoutineLibrary(listOf(own), "mine"))
        val viewModel = viewModel(repository, FakeFiles(toOpen = RoutineFileCodec.encode(every.take(2))))
        runCurrent()
        viewModel.import()
        runCurrent()
        val library = repository.library.value!!
        assertEquals(listOf("Mine", "Refresh", "Course steps"), library.routines.map { it.name })
        assertEquals(listOf("mine", "new-1", "new-2"), library.routines.map { it.id })
        assertEquals("new-1", library.selected?.id)
        assertTrue(viewModel.messages.first().startsWith("Imported 2 routines."))
    }

    @Test
    fun `given a file with nothing usable when imported then nothing is added and it says so`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary())
        val viewModel = viewModel(repository, FakeFiles(toOpen = "{}"))
        runCurrent()
        viewModel.import()
        runCurrent()
        assertTrue(repository.library.value!!.routines.isEmpty())
        assertEquals("That file has no routines AutoClicker can use.", viewModel.messages.first())
    }

    @Test
    fun `given the open dialog cancelled when importing then nothing happens`() = test {
        val repository = FakeRoutineRepository(RoutineLibrary())
        val viewModel = viewModel(repository, FakeFiles(toOpen = null))
        runCurrent()
        viewModel.import()
        runCurrent()
        assertTrue(repository.library.value!!.routines.isEmpty())
    }
}
