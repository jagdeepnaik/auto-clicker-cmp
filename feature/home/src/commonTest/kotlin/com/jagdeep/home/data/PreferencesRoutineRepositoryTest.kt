package com.jagdeep.home.data

import com.jagdeep.home.domain.ClickPoint
import com.jagdeep.home.domain.ClickRoutine
import com.jagdeep.home.domain.ClickTarget
import com.jagdeep.home.domain.PageHelperRoutine
import com.jagdeep.home.domain.ScrollClickRoutine
import com.jagdeep.home.domain.RunLimit
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PreferencesRoutineRepositoryTest {
    private val full = ClickRoutine(
        id = "r",
        name = "Full",
        target = ClickTarget.AtPoints(listOf(ClickPoint(1, 2), ClickPoint(3, 4, delayMillis = 250, button = com.jagdeep.automation.PointerButton.Middle))),
        limit = RunLimit.Passes(3),
        onlyInApp = "Google Chrome",
    )

    private fun TestScope.repository(store: InMemoryPreferencesStore<String>) =
        PreferencesRoutineRepository(store, backgroundScope).also { runCurrent() }

    @Test
    fun `given a first launch when read then one ready routine is selected`() = runTest {
        val library = repository(InMemoryPreferencesStore("")).library.value!!
        assertEquals(1, library.routines.size)
        assertEquals(library.routines.single(), library.selected)
    }

    @Test
    fun `given a saved routine when read back by a new instance then every field survives`() = runTest {
        val store = InMemoryPreferencesStore("")
        repository(store).save(full)
        runCurrent()
        val reloaded = repository(store).library.value!!
        assertEquals(full, reloaded.routines.last())
        assertEquals(full, reloaded.selected)
    }

    @Test
    fun `given an edited routine when saved then it replaces the old one in place`() = runTest {
        val repository = repository(InMemoryPreferencesStore(""))
        repository.save(full)
        repository.save(full.copy(name = "Edited"))
        runCurrent()
        assertEquals(listOf("Click at the pointer", "Edited"), repository.library.value!!.routines.map { it.name })
    }

    @Test
    fun `given every routine deleted when reopened then the first-launch routine doesn't come back`() = runTest {
        val store = InMemoryPreferencesStore("")
        val repository = repository(store)
        repository.library.value!!.routines.forEach { repository.delete(it.id) }
        runCurrent()
        val reloaded = repository(store).library.value!!
        assertTrue(reloaded.routines.isEmpty())
        assertNull(reloaded.selected)
    }

    @Test
    fun `given a selection when another routine is deleted then the selection stays`() = runTest {
        val repository = repository(InMemoryPreferencesStore(""))
        repository.save(full)
        repository.select("r")
        repository.delete("routine-first")
        runCurrent()
        assertEquals(full, repository.library.value!!.selected)
    }

    @Test
    fun `given every kind of routine when saved and read back then each keeps its kind and fields`() = runTest {
        val store = InMemoryPreferencesStore("")
        val scroll = ScrollClickRoutine("s", "Next pages", ClickPoint(640, 880), rounds = 7, pixelsPerSecond = 120, watchVideos = false)
        val helper = PageHelperRoutine("h", "Course", words = listOf("Weiter"), soundOnVideoEnd = false)
        repository(store).run {
            save(full)
            save(scroll)
            save(helper)
        }
        runCurrent()
        val reloaded = repository(store).library.value!!
        assertEquals(listOf(full, scroll, helper), reloaded.routines.drop(1))
    }

    @Test
    fun `given routines saved before there were kinds when read then they load as click routines`() = runTest {
        val raw = """{"routines":[{"id":"old","name":"Saved last week","intervalMillis":500,"target":{"type":"points","points":[{"x":1,"y":2}]}}],"selectedId":"old"}"""
        val routine = repository(InMemoryPreferencesStore(raw)).library.value!!.selected
        assertEquals(ClickRoutine("old", "Saved last week", target = ClickTarget.AtPoints(listOf(ClickPoint(1, 2))), intervalMillis = 500), routine)
    }

    @Test
    fun `given one routine that can't be read when loading then the others still load`() = runTest {
        val raw = """{"routines":[{"type":"scrollClick","id":"broken"},{"id":"ok","name":"Fine"}],"selectedId":"ok"}"""
        val library = repository(InMemoryPreferencesStore(raw)).library.value!!
        assertEquals(listOf("Fine"), library.routines.map { it.name })
    }

    @Test
    fun `given an unknown future field when read then the routine still loads`() = runTest {
        val raw = """{"routines":[{"id":"x","name":"Old","colour":"red"}],"selectedId":"x","extra":1}"""
        assertEquals("Old", repository(InMemoryPreferencesStore(raw)).library.value!!.selected?.name)
    }
}
