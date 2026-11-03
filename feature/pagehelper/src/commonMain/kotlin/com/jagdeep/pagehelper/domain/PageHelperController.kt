package com.jagdeep.pagehelper.domain

import com.jagdeep.automation.DesktopAutomation
import com.jagdeep.automation.InputAccess
import com.jagdeep.automation.ScreenPoint
import com.jagdeep.localbridge.LocalBridge
import com.jagdeep.logger.Log
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.history.RunHistoryStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.shared.preferences.AppPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
data class PageHelperSettings(
    val enabled: Boolean = false,
    /** Button words in priority order. */
    val words: List<String> = DEFAULT_WORDS,
    val pixelsPerSecond: Int = DEFAULT_PIXELS_PER_SECOND,
    val soundOnVideoEnd: Boolean = true,
    /** Scroll and click: the screen position to click at the bottom of each page. */
    val clickPoint: SavedPoint? = null,
    val scrollClickRounds: Int = DEFAULT_SCROLL_CLICK_ROUNDS,
    /** Scroll and click: stops for each video it reaches, plays it, and goes on when it ends. */
    val watchVideos: Boolean = true,
)

@Serializable
data class SavedPoint(val x: Int, val y: Int)

val DEFAULT_WORDS = listOf("Next", "Continue", "Play", "Watch video")
const val MIN_PIXELS_PER_SECOND = 10
const val MAX_PIXELS_PER_SECOND = 400
const val DEFAULT_PIXELS_PER_SECOND = 60
const val MIN_SCROLL_CLICK_ROUNDS = 1
const val MAX_SCROLL_CLICK_ROUNDS = 9_999
const val DEFAULT_SCROLL_CLICK_ROUNDS = 20
internal const val SCROLL_CLICK_RUN_NAME = "Scroll and click"

data class PageHelperState(
    val isSupported: Boolean = true,
    val settings: PageHelperSettings = PageHelperSettings(),
    val isConnected: Boolean = false,
    val problem: String? = null,
    val isScrolling: Boolean = false,
    /** The label of the button highlighted in Chrome right now. */
    val foundLabel: String? = null,
    /** Waiting for the capture key, which records the spot to click. */
    val isPickingPoint: Boolean = false,
    /** Set while a scroll and click run is going. */
    val scrollClick: ScrollClickProgress? = null,
    /** How the last run ended, or why one couldn't start. */
    val scrollClickNote: String? = null,
)

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

/**
 * Process-scoped, because its keys have to work while Chrome is in front. The extension never acts
 * by itself: each of its clicks is one press of the user's key. The one thing that clicks without a
 * key press is a scroll and click run, which the user starts here and which clicks through the
 * operating system at the spot they picked.
 */
class PageHelperController(
    private val automation: DesktopAutomation,
    private val bridge: LocalBridge,
    private val store: PreferencesStore<String>,
    preferences: Flow<AppPreferences>,
    private val scope: CoroutineScope,
    private val history: RunHistoryStore,
    private val now: () -> Long,
    private val newId: () -> String,
    /** True while a routine is clicking, when a second source of clicks must not start. */
    private val isOtherRunActive: () -> Boolean = { false },
) {
    private val lock = Mutex()
    private val pageReading = MutableStateFlow<PageReading?>(null)
    private var scrollClickJob: Job? = null
    private val scrollClickRun = ScrollClickRun(automation.pointer, automation.access) {
        val current = state.value
        if (!current.isConnected || !current.settings.enabled) RunOutcome.BrowserDisconnected else null
    }
    private val currentPreferences: StateFlow<AppPreferences> = preferences.stateIn(scope, SharingStarted.Eagerly, AppPreferences())
    private val mutableState = MutableStateFlow(PageHelperState(isSupported = automation.access.access() != InputAccess.Unsupported))
    private var advance = 0L

    val state: StateFlow<PageHelperState> = mutableState.asStateFlow()
    val preferences: StateFlow<AppPreferences> = currentPreferences

    init {
        publish()
        scope.launch {
            store.data.collect { raw ->
                val settings = decode(raw)
                mutableState.update { it.copy(settings = settings, isScrolling = it.isScrolling && settings.enabled) }
                publish()
            }
        }
        scope.launch {
            bridge.connected.collect { connected ->
                // Nothing is highlighted, as far as anyone knows, without a connection.
                mutableState.update { it.copy(isConnected = connected, foundLabel = it.foundLabel.takeIf { connected }) }
            }
        }
        scope.launch { bridge.problem.collect { problem -> mutableState.update { it.copy(problem = problem) } } }
        scope.launch { bridge.incoming.collect(::onMessage) }
        scope.launch {
            automation.hotKeys.presses.collect { key ->
                val prefs = currentPreferences.value
                when (key) {
                    prefs.pageClickHotKey -> clickHighlighted()
                    prefs.pageScrollHotKey -> toggleScrolling()
                    prefs.captureHotKey -> recordPickedPoint()
                    else -> Unit
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = updateSettings { it.copy(enabled = enabled) }

    fun setWords(words: List<String>) = updateSettings { it.copy(words = words) }

    fun setSpeed(pixelsPerSecond: Int) =
        updateSettings { it.copy(pixelsPerSecond = pixelsPerSecond.coerceIn(MIN_PIXELS_PER_SECOND, MAX_PIXELS_PER_SECOND)) }

    fun setSoundOnVideoEnd(enabled: Boolean) = updateSettings { it.copy(soundOnVideoEnd = enabled) }

    fun setScrollClickRounds(rounds: Int) =
        updateSettings { it.copy(scrollClickRounds = rounds.coerceIn(MIN_SCROLL_CLICK_ROUNDS, MAX_SCROLL_CLICK_ROUNDS)) }

    fun setWatchVideos(enabled: Boolean) = updateSettings { it.copy(watchVideos = enabled) }

    /** The next press of the capture key records where the pointer is. */
    fun startPickingPoint() = mutableState.update { it.copy(isPickingPoint = true) }

    fun cancelPickingPoint() = mutableState.update { it.copy(isPickingPoint = false) }

    fun startScrollClick() {
        val current = state.value
        if (current.scrollClick != null) return
        val point = current.settings.clickPoint
        val problem = when {
            !current.settings.enabled -> "Turn on the page helper first."
            !current.isConnected -> "Open Chrome with the extension added first."
            point == null -> "Pick the spot to click first."
            isOtherRunActive() -> "Stop the running routine first."
            automation.access.access() != InputAccess.Granted -> "Allow Accessibility access so clicks can reach Chrome."
            else -> null
        }
        if (problem != null || point == null) {
            mutableState.update { it.copy(scrollClickNote = problem) }
            return
        }
        val rounds = current.settings.scrollClickRounds
        pageReading.value = null
        // Set before launching, so a second press can never start a second run.
        mutableState.update {
            it.copy(
                scrollClick = ScrollClickProgress(1, rounds, 0, ScrollClickPhase.WaitingForChrome),
                scrollClickNote = null,
                isPickingPoint = false,
                isScrolling = true,
            )
        }
        publish()
        scrollClickJob = scope.launch { performScrollClick(ScreenPoint(point.x, point.y), rounds) }.also { started ->
            // Cancelled before its first dispatch, the body never runs to reset this.
            started.invokeOnCompletion { if (state.value.scrollClick != null) endScrollClick(null) }
        }
    }

    /** Returns true when there was a run to stop, so the same key press isn't also used for something else. */
    fun stopScrollClick(): Boolean {
        val active = state.value.scrollClick != null
        if (active) scrollClickJob?.cancel()
        return active
    }

    private suspend fun performScrollClick(point: ScreenPoint, rounds: Int) {
        val startedAt = now()
        var clicks = 0L
        var outcome = RunOutcome.StoppedByUser
        val prefs = currentPreferences.value
        try {
            outcome = scrollClickRun.execute(
                point = point,
                rounds = rounds,
                safety = ScrollClickSafety(prefs.stopWhenPointerMoves, prefs.stopAtScreenCorner),
                pages = pageReading,
                setScrolling = ::setRunScrolling,
                onProgress = { progress ->
                    clicks = progress.clicks
                    mutableState.update { it.copy(scrollClick = progress) }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("Scroll and click failed: ${e.message}", TAG)
            outcome = RunOutcome.Failed
        } finally {
            withContext(NonCancellable) {
                endScrollClick("${outcome.label}. Clicks: $clicks.")
                history.add(RunRecord(newId(), SCROLL_CLICK_RUN_NAME, startedAt, now(), clicks, outcome))
            }
        }
    }

    private fun setRunScrolling(scrolling: Boolean) {
        if (state.value.isScrolling == scrolling) return
        mutableState.update { it.copy(isScrolling = scrolling) }
        publish()
    }

    private fun endScrollClick(note: String?) {
        mutableState.update { it.copy(scrollClick = null, isScrolling = false, scrollClickNote = note ?: it.scrollClickNote) }
        publish()
    }

    private fun recordPickedPoint() {
        if (!state.value.isPickingPoint) return
        val position = automation.pointer.position() ?: return
        mutableState.update { it.copy(isPickingPoint = false) }
        updateSettings { it.copy(clickPoint = SavedPoint(position.x, position.y)) }
    }

    /** During a scroll and click run, this is its stop control. */
    fun toggleScrolling() {
        if (stopScrollClick()) return
        if (!state.value.settings.enabled) return
        mutableState.update { it.copy(isScrolling = !it.isScrolling) }
        publish()
    }

    private fun clickHighlighted() {
        if (!state.value.settings.enabled || state.value.foundLabel == null || state.value.scrollClick != null) return
        advance++
        publish()
    }

    private fun onMessage(raw: String) {
        val event = try {
            json.decodeFromString(HelperEvent.serializer(), raw)
        } catch (_: SerializationException) {
            return
        } catch (_: IllegalArgumentException) {
            return
        }
        when (event.type) {
            "found" -> mutableState.update { it.copy(foundLabel = event.label?.take(MAX_LABEL_LENGTH)) }
            "videoEnded" -> if (state.value.settings.soundOnVideoEnd) automation.playAlert()
            "page" -> pageReading.value = PageReading(event.focused, event.atBottom, event.signature?.take(MAX_SIGNATURE_LENGTH), pageVideo(event.video))
        }
    }

    private fun pageVideo(reported: String?): PageVideo = when (reported) {
        "playing" -> PageVideo.Playing
        "needsPlay" -> PageVideo.NeedsPlay
        else -> PageVideo.None
    }

    private fun updateSettings(change: (PageHelperSettings) -> PageHelperSettings) {
        val updated = change(state.value.settings)
        mutableState.update { it.copy(settings = updated, isScrolling = it.isScrolling && updated.enabled) }
        publish()
        scope.launch { lock.withLock { store.save(json.encodeToString(PageHelperSettings.serializer(), updated)) } }
    }

    private fun publish() {
        val current = state.value
        val command = HelperCommand(
            enabled = current.settings.enabled,
            scrolling = current.isScrolling,
            pixelsPerSecond = current.settings.pixelsPerSecond,
            words = current.settings.words,
            advance = advance,
            autoRun = current.scrollClick != null,
            watchVideos = current.settings.watchVideos,
        )
        bridge.publish(json.encodeToString(HelperCommand.serializer(), command))
    }

    private fun decode(raw: String): PageHelperSettings =
        if (raw.isBlank()) PageHelperSettings() else runCatching { json.decodeFromString(PageHelperSettings.serializer(), raw) }.getOrDefault(PageHelperSettings())

    private companion object {
        const val TAG = "PageHelperController"
        const val MAX_LABEL_LENGTH = 60
        const val MAX_SIGNATURE_LENGTH = 128
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** "Next, Continue , , Play" becomes the words in that order. */
fun parseWords(text: String): List<String> =
    text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
