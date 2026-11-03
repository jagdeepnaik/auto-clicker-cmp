package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.ForegroundApps
import com.jagdeep.automation.HotKey
import com.jagdeep.automation.HotKeyMonitor
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.InputAccessGate
import com.jagdeep.automation.PointerButton
import com.jagdeep.automation.PointerDevice
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

internal data class SentClick(val at: ScreenPoint?, val button: PointerButton, val count: Int)

internal class FakeAutomation(
    initialPosition: ScreenPoint = ScreenPoint(500, 500),
    var inputAccess: InputAccess = InputAccess.Granted,
) : DesktopAutomation {
    val clicks = mutableListOf<SentClick>()
    var position: ScreenPoint? = initialPosition
    var clickWorks = true
    var clickThrows = false
    var corners = emptySet<ScreenPoint>()
    var ownWindowCovers = emptySet<ScreenPoint>()
    var front: String? = "Google Chrome"
    var appList = listOf("Google Chrome", "Finder")
    var accessRequests = 0
    val keyPresses = MutableSharedFlow<HotKey>(extraBufferCapacity = 8)

    override val pointer = object : PointerDevice {
        override fun position() = this@FakeAutomation.position

        override fun click(at: ScreenPoint?, button: PointerButton, count: Int): Boolean {
            if (clickThrows) error("input device failed")
            if (!clickWorks) return false
            if (at != null) this@FakeAutomation.position = at
            clicks += SentClick(at, button, count)
            return true
        }

        override fun isScreenCorner(point: ScreenPoint) = point in corners
        override fun isOverOwnFocusedWindow(point: ScreenPoint) = point in ownWindowCovers
    }
    override val access = object : InputAccessGate {
        override fun access() = inputAccess
        override fun requestAccess(): Boolean {
            accessRequests++
            return true
        }
    }
    override val hotKeys = object : HotKeyMonitor {
        override val presses: Flow<HotKey> = keyPresses
    }
    override val apps = object : ForegroundApps {
        override fun frontmost() = front
        override fun running() = appList
    }

    override fun close() = Unit
}

internal class FakeRoutineRepository(initial: RoutineLibrary? = RoutineLibrary()) : RoutineRepository {
    private val mutable = MutableStateFlow(initial)
    override val library: StateFlow<RoutineLibrary?> = mutable

    override suspend fun save(routine: ClickRoutine) {
        val current = mutable.value ?: RoutineLibrary()
        mutable.value = current.copy(routines = current.routines.filterNot { it.id == routine.id } + routine, selectedId = routine.id)
    }

    override suspend fun delete(id: String) {
        val current = mutable.value ?: return
        mutable.value = current.copy(routines = current.routines.filterNot { it.id == id })
    }

    override suspend fun select(id: String) {
        mutable.value = (mutable.value ?: RoutineLibrary()).copy(selectedId = id)
    }
}

internal class FakeRunHistory : RunHistoryStore {
    val added = mutableListOf<RunRecord>()
    override val records = MutableStateFlow<List<RunRecord>>(emptyList())

    override suspend fun add(record: RunRecord) {
        added += record
        records.value = listOf(record) + records.value
    }

    override suspend fun clear() {
        added.clear()
        records.value = emptyList()
    }
}
