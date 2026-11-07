package com.jagdeep.home.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.logger.Log
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.live.LiveLog
import com.jagdeep.shared.live.LiveSource
import com.jagdeep.shared.live.NoLiveLog
import com.jagdeep.shared.live.log
import com.jagdeep.shared.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The Chrome routine that's running. */
sealed interface PageActivity {
    val routine: Routine

    /** Highlighting is on; [clicks] counts the key presses that clicked a button. */
    data class Helper(override val routine: PageHelperRoutine, val clicks: Long = 0) : PageActivity

    data class ScrollClick(override val routine: ScrollClickRoutine, val progress: ScrollClickProgress) : PageActivity
}

data class PageState(
    val isSupported: Boolean = true,
    val isConnected: Boolean = false,
    val problem: String? = null,
    val isScrolling: Boolean = false,
    /** The label of the button highlighted in Chrome right now. */
    val foundLabel: String? = null,
    val activity: PageActivity? = null,
)

/** Runs the routines that work inside Chrome. [ClickController] uses it so only one routine runs at a time. */
interface PageRunner {
    val state: StateFlow<PageState>

    /** The most recent Chrome routine finished since the app opened. */
    val lastRun: StateFlow<RunRecord?>

    val isActive: Boolean get() = state.value.activity != null

    /** Starts a Chrome routine. Returns why it can't start, or null when it started. */
    fun start(routine: Routine): String?

    /** Returns true when there was a routine to stop. */
    fun stop(): Boolean

    /** Starts or pauses scrolling during a page helper routine. */
    fun toggleScrolling()
}

/** Where there's no Chrome link, as in tests of other routines. */
object NoPageRunner : PageRunner {
    override val state: StateFlow<PageState> = MutableStateFlow(PageState(isSupported = false)).asStateFlow()
    override val lastRun: StateFlow<RunRecord?> = MutableStateFlow<RunRecord?>(null).asStateFlow()
    override fun start(routine: Routine): String = "This kind of routine works with Chrome on a computer."
    override fun stop() = false
    override fun toggleScrolling() = Unit
}

/** What the extension is told. [advance] counts key presses: a click only ever follows an increase. */
@Serializable
internal data class HelperCommand(
    val enabled: Boolean,
    val scrolling: Boolean,
    val pixelsPerSecond: Int,
    val words: List<String>,
    val advance: Long,
    /** The app clicks by itself in this mode, so the extension reports where the page is. */
    val autoRun: Boolean = false,
    val watchVideos: Boolean = false,
)

@Serializable
internal data class HelperEvent(
    val type: String,
    val label: String? = null,
    val focused: Boolean = false,
    val atBottom: Boolean = false,
    val signature: String? = null,
    val video: String? = null,
)

internal const val BROWSER_APP_NAME = "Google Chrome"

/**
 * Process-scoped, because its keys have to work while Chrome is in front. The extension is told to
 * act only while a Chrome routine runs. Its own clicks each follow a press of the user's key; a scroll
 * and click routine clicks through the operating system at the spot the user picked.
 */
class PageController(
    private val automation: DesktopAutomation,
    private val bridge: LocalBridge,
    preferences: Flow<AppPreferences>,
    private val scope: CoroutineScope,
    private val history: RunHistoryStore,
    private val now: () -> Long,
    private val newId: () -> String,
    private val live: LiveLog = NoLiveLog,
) : PageRunner {
    private val pageReading = MutableStateFlow<PageReading?>(null)
    private val currentPreferences: StateFlow<AppPreferences> = preferences.stateIn(scope, SharingStarted.Eagerly, AppPreferences())
    private val mutableState = MutableStateFlow(PageState(isSupported = automation.access.access() != InputAccess.Unsupported))
    private val mutableLastRun = MutableStateFlow<RunRecord?>(null)
    private val scrollClickRun = ScrollClickRun(automation.pointer, automation.access) {
        if (!state.value.isConnected) RunOutcome.BrowserDisconnected else null
    }
    private var job: Job? = null
    private var advance = 0L
    private var lastPublished: String? = null

    override val state: StateFlow<PageState> = mutableState.asStateFlow()
    override val lastRun: StateFlow<RunRecord?> = mutableLastRun.asStateFlow()

    init {
        publish()
        scope.launch {
            bridge.connected.collect { connected ->
                live.log(LiveSource.Chrome) { if (connected) "Extension connected" else "Extension not connected" }
                // Nothing is highlighted, as far as anyone knows, without a connection.
                mutableState.update { it.copy(isConnected = connected, foundLabel = it.foundLabel.takeIf { connected }) }
            }
        }
        scope.launch {
            bridge.problem.collect { problem ->
                if (problem != null) live.log(LiveSource.Chrome) { "Link problem: $problem" }
                mutableState.update { it.copy(problem = problem) }
            }
        }
        scope.launch { bridge.incoming.collect(::onMessage) }
        scope.launch {
            automation.hotKeys.presses.collect { key ->
                val prefs = currentPreferences.value
                when (key) {
                    prefs.pageClickHotKey -> clickHighlighted()
                    prefs.pageScrollHotKey -> toggleScrolling()
                    else -> Unit
                }
            }
        }
    }

    override fun start(routine: Routine): String? {
        if (isActive) return null
        val refusal = startOrRefuse(routine)
        live.log(LiveSource.Routine) {
            if (refusal == null) "Started ${routine.name} (${routine.kind.label})" else "Didn't start ${routine.name}: $refusal"
        }
        return refusal
    }

    private fun startOrRefuse(routine: Routine): String? {
        state.value.problem?.let { return it }
        if (!state.value.isConnected) return NOT_CONNECTED
        return when (routine) {
            is PageHelperRoutine -> {
                launchActivity(PageActivity.Helper(routine), scrolling = false) { performHelper(routine) }
                null
            }
            is ScrollClickRoutine -> {
                if (automation.access.access() != InputAccess.Granted) return "Allow Accessibility access so clicks can reach Chrome."
                pageReading.value = null
                val progress = ScrollClickProgress(1, routine.rounds, 0, ScrollClickPhase.ReadingPage)
                launchActivity(PageActivity.ScrollClick(routine, progress), scrolling = true) { performScrollClick(routine) }
                null
            }
            is ClickRoutine -> "A click routine doesn't run in Chrome."
        }
    }

    override fun stop(): Boolean {
        val active = isActive
        if (active) job?.cancel()
        return active
    }

    /** During a scroll and click routine, this is its stop control. */
    override fun toggleScrolling() {
        when (state.value.activity) {
            is PageActivity.ScrollClick -> {
                live.log(LiveSource.Keys) { "Scroll key: stopping scroll and click" }
                stop()
            }
            is PageActivity.Helper -> {
                mutableState.update { it.copy(isScrolling = !it.isScrolling) }
                live.log(LiveSource.Keys) { if (state.value.isScrolling) "Scroll key: scrolling on" else "Scroll key: scrolling off" }
                publish()
            }
            null -> Unit
        }
    }

    private fun launchActivity(activity: PageActivity, scrolling: Boolean, body: suspend () -> Unit) {
        // Set before launching, so a second press can never start a second routine.
        mutableState.update { it.copy(activity = activity, isScrolling = scrolling) }
        publish()
        job = scope.launch { body() }.also { started ->
            // Cancelled before its first dispatch, the body never runs to reset this.
            started.invokeOnCompletion { if (state.value.activity != null) endActivity() }
        }
    }

    private suspend fun performHelper(routine: PageHelperRoutine) {
        val startedAt = now()
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                val clicks = (state.value.activity as? PageActivity.Helper)?.clicks ?: 0
                endActivity()
                record(routine.name, startedAt, clicks, RunOutcome.StoppedByUser)
            }
        }
    }

    private suspend fun performScrollClick(routine: ScrollClickRoutine) {
        val startedAt = now()
        var clicks = 0L
        var outcome = RunOutcome.StoppedByUser
        val prefs = currentPreferences.value
        try {
            // Start is pressed in this app, and nothing is clicked until Chrome is in front.
            live.log(LiveSource.App) { "Bringing $BROWSER_APP_NAME to the front" }
            automation.apps.bringToFront(BROWSER_APP_NAME)
            outcome = scrollClickRun.execute(
                point = ScreenPoint(routine.point.x, routine.point.y),
                rounds = routine.rounds,
                safety = ScrollClickSafety(prefs.stopWhenPointerMoves, prefs.stopAtScreenCorner),
                pages = pageReading,
                setScrolling = ::setRunScrolling,
                onProgress = { progress ->
                    val previous = (state.value.activity as? PageActivity.ScrollClick)?.progress
                    if (progress.clicks > clicks) {
                        live.log(LiveSource.Click) { "Clicked at ${routine.point.x}, ${routine.point.y} (round ${progress.round} of ${progress.rounds})" }
                    }
                    if (previous == null || previous.phase != progress.phase || previous.round != progress.round) {
                        live.log(LiveSource.Routine) { "Round ${progress.round} of ${progress.rounds}: ${progress.phase.name}" }
                    }
                    clicks = progress.clicks
                    mutableState.update { it.copy(activity = PageActivity.ScrollClick(routine, progress)) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Scroll and click failed: ${e.message}", TAG)
            outcome = RunOutcome.Failed
        } finally {
            withContext(NonCancellable) {
                endActivity()
                record(routine.name, startedAt, clicks, outcome)
            }
        }
    }

    private suspend fun record(name: String, startedAt: Long, clicks: Long, outcome: RunOutcome) {
        val record = RunRecord(newId(), name, startedAt, now(), clicks, outcome)
        live.log(LiveSource.Routine) { "Ended $name: ${outcome.label}, $clicks clicks" }
        mutableLastRun.value = record
        history.add(record)
    }

    private fun setRunScrolling(scrolling: Boolean) {
        if (state.value.isScrolling == scrolling) return
        mutableState.update { it.copy(isScrolling = scrolling) }
        publish()
    }

    private fun endActivity() {
        mutableState.update { it.copy(activity = null, isScrolling = false) }
        publish()
    }

    private fun clickHighlighted() {
        val helper = state.value.activity as? PageActivity.Helper ?: return
        val label = state.value.foundLabel
        if (label == null) {
            live.log(LiveSource.Keys) { "Click key: nothing highlighted, so no click" }
            return
        }
        live.log(LiveSource.Keys) { "Click key: asking Chrome to click \"$label\"" }
        advance++
        mutableState.update { it.copy(activity = helper.copy(clicks = helper.clicks + 1)) }
        publish()
    }

    private fun onMessage(raw: String) {
        live.log(LiveSource.Chrome) { "← ${raw.take(MAX_LOGGED_MESSAGE)}" }
        val event = try {
            json.decodeFromString(HelperEvent.serializer(), raw)
        } catch (_: SerializationException) {
            live.log(LiveSource.Chrome) { "Ignored a message that isn't valid" }
            return
        } catch (_: IllegalArgumentException) {
            live.log(LiveSource.Chrome) { "Ignored a message that isn't valid" }
            return
        }
        when (event.type) {
            "found" -> mutableState.update { it.copy(foundLabel = event.label?.take(MAX_LABEL_LENGTH)) }
            "videoEnded" -> {
                val helper = state.value.activity as? PageActivity.Helper
                if (helper?.routine?.soundOnVideoEnd == true) automation.playAlert()
            }
            "page" -> pageReading.value = PageReading(event.focused, event.atBottom, event.signature?.take(MAX_SIGNATURE_LENGTH), pageVideo(event.video))
        }
    }

    private fun pageVideo(reported: String?): PageVideo = when (reported) {
        "playing" -> PageVideo.Playing
        "needsPlay" -> PageVideo.NeedsPlay
        else -> PageVideo.None
    }

    private fun publish() {
        val current = state.value
        val activity = current.activity
        val command = HelperCommand(
            enabled = activity != null,
            scrolling = current.isScrolling,
            pixelsPerSecond = when (activity) {
                is PageActivity.Helper -> activity.routine.pixelsPerSecond
                is PageActivity.ScrollClick -> activity.routine.pixelsPerSecond
                null -> DEFAULT_PIXELS_PER_SECOND
            },
            words = (activity as? PageActivity.Helper)?.routine?.words.orEmpty(),
            advance = advance,
            autoRun = activity is PageActivity.ScrollClick,
            watchVideos = (activity as? PageActivity.ScrollClick)?.routine?.watchVideos ?: false,
        )
        val encoded = json.encodeToString(HelperCommand.serializer(), command)
        if (encoded != lastPublished) live.log(LiveSource.Chrome) { "→ $encoded" }
        lastPublished = encoded
        bridge.publish(encoded)
    }

    private companion object {
        const val TAG = "PageController"
        const val MAX_LABEL_LENGTH = 60
        const val MAX_SIGNATURE_LENGTH = 128
        const val MAX_LOGGED_MESSAGE = 300
        const val NOT_CONNECTED = "Open Chrome with the extension added first. The steps are in Settings."
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
