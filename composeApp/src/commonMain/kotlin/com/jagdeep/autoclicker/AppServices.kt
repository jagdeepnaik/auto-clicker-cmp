package com.jagdeep.autoclicker

import com.jagdeep.activity.data.PreferencesRunHistoryStore
import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.createDesktopAutomation
import com.jagdeep.common.time.AppClock
import com.jagdeep.home.data.PreferencesRoutineRepository
import com.jagdeep.home.domain.ClickController
import com.jagdeep.home.domain.PageController
import com.jagdeep.home.domain.RoutineRepository
import com.jagdeep.home.domain.RunState
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.localbridge.createLocalBridge
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.preferences.AppPreferences
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.jagdeep.shared.live.LiveSource
import com.jagdeep.shared.live.log
import kotlinx.coroutines.launch

/** The display stays on only when the user asked for that and something is running. */
internal fun shouldKeepDisplayAwake(enabled: Boolean, routineActive: Boolean, pageHelperActive: Boolean): Boolean =
    enabled && (routineActive || pageHelperActive)

/**
 * Process-scoped services: created once, independent of any screen, so the hot keys and a running
 * routine keep working whichever tab is open. Desktop shuts these down when its window closes.
 */
internal class AppServices private constructor() {
    // Default, not Main: each click blocks its thread for a few milliseconds.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences: PreferencesStore<AppPreferences> = createAppPreferencesStore()
    val automation: DesktopAutomation = createDesktopAutomation()
    val routines: RoutineRepository = PreferencesRoutineRepository(createDocumentStore(ROUTINES_STORE), scope)
    val runHistory: RunHistoryStore = PreferencesRunHistoryStore(createDocumentStore(RUN_HISTORY_STORE))
    val liveLog = MemoryLiveLog(AppClock::nowMillis)
    private val bridge: LocalBridge = createLocalBridge(BROWSER_LINK_PORT, BROWSER_EXTENSION_ORIGIN)
    val page = PageController(
        automation = automation,
        bridge = bridge,
        preferences = preferences.data,
        scope = scope,
        history = runHistory,
        now = AppClock::nowMillis,
        newId = ::newId,
        live = liveLog,
    )

    // Runs every kind of routine, one at a time, handing Chrome routines to [page].
    val controller: ClickController = ClickController(
        automation = automation,
        repository = routines,
        preferences = preferences.data,
        history = runHistory,
        scope = scope,
        now = AppClock::nowMillis,
        newId = ::newId,
        page = page,
        live = liveLog,
    )

    init {
        scope.launch {
            preferences.data.map { it.developerMode }.distinctUntilChanged().collect { on ->
                liveLog.recording = on
                liveLog.log(LiveSource.App) { "Developer mode on: recording what the app does" }
            }
        }
        // Launched last: every service it reads exists by now.
        scope.launch {
            combine(preferences.data, controller.state, page.state) { prefs, run, chrome ->
                shouldKeepDisplayAwake(
                    enabled = prefs.keepDisplayOn,
                    routineActive = run != RunState.Idle,
                    pageHelperActive = chrome.activity != null,
                )
            }.distinctUntilChanged().collect { awake ->
                liveLog.log(LiveSource.App) { if (awake) "Keeping the display on" else "Display may sleep again" }
                automation.setDisplayAwake(awake)
            }
        }
    }

    private fun close() {
        // A run interrupted here is recorded on a best-effort basis: the process is exiting.
        controller.stop()
        scope.cancel()
        bridge.close()
        automation.close()
    }

    companion object {
        private const val ROUTINES_STORE = "routines"
        private const val RUN_HISTORY_STORE = "run_history"
        private var instance: AppServices? = null

        /** The process-wide services, created on first use (after platform start-up, e.g. Android's Application). */
        fun get(): AppServices = instance ?: AppServices().also { instance = it }

        /** Stops any run and the hot keys; called when the desktop window closes. */
        fun shutdown() {
            instance?.close()
            instance = null
        }

        fun newId(): String = Random.nextLong().toULong().toString(16)
    }
}
