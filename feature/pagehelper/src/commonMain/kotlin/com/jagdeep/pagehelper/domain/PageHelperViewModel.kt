package com.jagdeep.pagehelper.domain

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jagdeep.automation.HotKey
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PageHelperUiState(
    val helper: PageHelperState = PageHelperState(),
    val wordsDraft: String = DEFAULT_WORDS.joinToString(", "),
    val wordsError: String? = null,
    val clickKey: HotKey = HotKey.F8,
    val scrollKey: HotKey = HotKey.F9,
    val captureKey: HotKey = HotKey.F7,
    val stopKey: HotKey = HotKey.F6,
    val roundsDraft: String = DEFAULT_SCROLL_CLICK_ROUNDS.toString(),
    val roundsError: String? = null,
)

class PageHelperViewModel(
    private val controller: PageHelperController,
    private val installExtension: suspend () -> String?,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PageHelperUiState())
    val state = mutableState.asStateFlow()
    private val mutableMessages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = mutableMessages.receiveAsFlow()

    init {
        viewModelScope.launch {
            controller.state.collect { helper ->
                mutableState.update { current ->
                    // Text being typed wins; the saved words only fill the field when they differ in meaning.
                    val draft = if (parseWords(current.wordsDraft) == helper.settings.words || current.wordsError != null) current.wordsDraft else helper.settings.words.joinToString(", ")
                    // The same rule for the rounds field.
                    val rounds = if (current.roundsDraft.toIntOrNull() == helper.settings.scrollClickRounds || current.roundsError != null) current.roundsDraft else helper.settings.scrollClickRounds.toString()
                    current.copy(helper = helper, wordsDraft = draft, roundsDraft = rounds)
                }
            }
        }
        viewModelScope.launch {
            controller.preferences.collect { prefs ->
                mutableState.update { it.copy(clickKey = prefs.pageClickHotKey, scrollKey = prefs.pageScrollHotKey, captureKey = prefs.captureHotKey, stopKey = prefs.toggleHotKey) }
            }
        }
    }

    fun setEnabled(enabled: Boolean) = controller.setEnabled(enabled)

    fun toggleScrolling() = controller.toggleScrolling()

    fun setSpeed(pixelsPerSecond: Int) = controller.setSpeed(pixelsPerSecond)

    fun setSoundOnVideoEnd(enabled: Boolean) = controller.setSoundOnVideoEnd(enabled)

    fun updateWords(text: String) {
        val words = parseWords(text)
        mutableState.update { it.copy(wordsDraft = text, wordsError = if (words.isEmpty()) "Add at least one word." else null) }
        if (words.isNotEmpty()) controller.setWords(words)
    }

    fun updateRounds(text: String) {
        val rounds = text.trim().toIntOrNull()?.takeIf { it in MIN_SCROLL_CLICK_ROUNDS..MAX_SCROLL_CLICK_ROUNDS }
        val error = if (rounds == null) "Enter a number from $MIN_SCROLL_CLICK_ROUNDS to $MAX_SCROLL_CLICK_ROUNDS." else null
        mutableState.update { it.copy(roundsDraft = text, roundsError = error) }
        if (rounds != null) controller.setScrollClickRounds(rounds)
    }

    fun setWatchVideos(enabled: Boolean) = controller.setWatchVideos(enabled)

    fun startPickingPoint() = controller.startPickingPoint()

    fun cancelPickingPoint() = controller.cancelPickingPoint()

    fun startScrollClick() = controller.startScrollClick()

    fun showExtension() {
        viewModelScope.launch { installExtension()?.let { mutableMessages.send(it) } }
    }
}
