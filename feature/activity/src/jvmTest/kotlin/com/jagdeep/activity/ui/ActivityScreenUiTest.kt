package com.jagdeep.activity.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.jagdeep.activity.data.PreferencesRunHistoryStore
import com.jagdeep.activity.domain.ActivityViewModel
import com.jagdeep.activity.ui.screens.ActivityScreen
import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.shared.history.RunOutcome
import com.jagdeep.shared.history.RunRecord
import com.jagdeep.ui.themes.AppTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalTestApi::class)
class ActivityScreenUiTest {
    private val now = 1_760_000_000_000L

    @Test
    fun `given no runs when shown then it explains what will appear`() = runDesktopComposeUiTest(1000, 700) {
        val viewModel = ActivityViewModel(PreferencesRunHistoryStore(InMemoryPreferencesStore("")), now = { now })
        setContent { AppTheme(darkTheme = false) { ActivityScreen(viewModel, onBack = {}) } }
        onNodeWithText("No runs yet").assertExists()
    }

    @Test
    fun `given developer mode when shown then the live log lists each step, and Runs shows the usual list`() = runDesktopComposeUiTest(1000, 700) {
        val live = object : com.jagdeep.shared.live.LiveLog {
            override val entries = kotlinx.coroutines.flow.MutableStateFlow(
                listOf(
                    com.jagdeep.shared.live.LiveEntry(1, now, com.jagdeep.shared.live.LiveSource.Chrome, "→ {\"enabled\":true}"),
                    com.jagdeep.shared.live.LiveEntry(2, now + 42, com.jagdeep.shared.live.LiveSource.Chrome, "← {\"type\":\"found\",\"label\":\"Next\"}"),
                ),
            )
            override val isRecording = true
            override fun add(source: com.jagdeep.shared.live.LiveSource, text: String) = Unit
            override fun clear() = Unit
        }
        val viewModel = ActivityViewModel(
            PreferencesRunHistoryStore(InMemoryPreferencesStore("")), now = { now }, live = live, developerMode = kotlinx.coroutines.flow.flowOf(true),
        )
        setContent { AppTheme(darkTheme = false) { androidx.compose.material3.Surface { ActivityScreen(viewModel, onBack = {}) } } }
        waitUntil(timeoutMillis = 5_000) { viewModel.state.value.liveEntries.size == 2 }
        onNodeWithText("← {\"type\":\"found\",\"label\":\"Next\"}").assertExists()
        onNodeWithText("2 entries · in memory only").assertExists()
        saveShot("activity-live")

        onNodeWithText("Runs").performClick()
        onNodeWithText("No runs yet").assertExists()
    }

    @Test
    fun `given past runs when shown then each says what happened`() = runDesktopComposeUiTest(1000, 700) {
        val store = PreferencesRunHistoryStore(InMemoryPreferencesStore(""))
        runBlocking {
            store.add(RunRecord("a", "Chrome button", now - 200_000, now, 200, RunOutcome.PointerMoved))
            store.add(RunRecord("b", "Refresh", now - 60_000, now, 1, RunOutcome.Completed))
        }
        val viewModel = ActivityViewModel(store, now = { now })
        setContent { AppTheme(darkTheme = true) { ActivityScreen(viewModel, onBack = {}) } }
        onNodeWithText("200 clicks in 3 min 20 s").assertExists()
        onNodeWithText("Stopped because you moved the pointer").assertExists()
        onNodeWithText("1 click in 1 min").assertExists()
        saveShot("activity")
    }
}

/** Set UI_TEST_SHOT_DIR to save what a test rendered, for reviewing a layout by eye. */
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.saveShot(name: String) {
    val directory = System.getenv("UI_TEST_SHOT_DIR") ?: return
    waitForIdle()
    ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(directory, "$name.png"))
}

