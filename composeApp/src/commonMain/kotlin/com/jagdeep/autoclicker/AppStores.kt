package com.jagdeep.autoclicker

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jagdeep.automation.HotKey
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.preferences.AppPreferences
import com.jagdeep.shared.preferences.ColorPalette
import com.jagdeep.shared.preferences.ThemeMode

/** Theme, palette, hot keys and safety switches (non-sensitive). One instance per process. */
expect fun createAppPreferencesStore(): PreferencesStore<AppPreferences>

/** One JSON document in its own file (non-sensitive). Call once per [name] per process. */
expect fun createDocumentStore(name: String): PreferencesStore<String>

internal const val APP_PREFERENCES_FILE = "app_preferences.preferences_pb"

internal fun documentFileName(name: String) = "$name.preferences_pb"

private val THEME_MODE = stringPreferencesKey("theme_mode")
private val COLOR_PALETTE = stringPreferencesKey("color_palette")
private val TOGGLE_HOT_KEY = stringPreferencesKey("toggle_hot_key")
private val CAPTURE_HOT_KEY = stringPreferencesKey("capture_hot_key")
private val PAGE_CLICK_HOT_KEY = stringPreferencesKey("page_click_hot_key")
private val PAGE_SCROLL_HOT_KEY = stringPreferencesKey("page_scroll_hot_key")
private val STOP_WHEN_POINTER_MOVES = booleanPreferencesKey("stop_when_pointer_moves")
private val STOP_AT_SCREEN_CORNER = booleanPreferencesKey("stop_at_screen_corner")
private val KEEP_DISPLAY_ON = booleanPreferencesKey("keep_display_on")
internal val DOCUMENT_KEY = stringPreferencesKey("document")

/** Absent or unknown values fall back to the model's defaults. */
internal fun readAppPreferences(stored: Preferences): AppPreferences {
    val defaults = AppPreferences()
    return AppPreferences(
        themeMode = enumOrDefault(stored[THEME_MODE], defaults.themeMode),
        colorPalette = enumOrDefault(stored[COLOR_PALETTE], defaults.colorPalette),
        toggleHotKey = enumOrDefault<HotKey>(stored[TOGGLE_HOT_KEY], defaults.toggleHotKey),
        captureHotKey = enumOrDefault<HotKey>(stored[CAPTURE_HOT_KEY], defaults.captureHotKey),
        pageClickHotKey = enumOrDefault<HotKey>(stored[PAGE_CLICK_HOT_KEY], defaults.pageClickHotKey),
        pageScrollHotKey = enumOrDefault<HotKey>(stored[PAGE_SCROLL_HOT_KEY], defaults.pageScrollHotKey),
        stopWhenPointerMoves = stored[STOP_WHEN_POINTER_MOVES] ?: defaults.stopWhenPointerMoves,
        stopAtScreenCorner = stored[STOP_AT_SCREEN_CORNER] ?: defaults.stopAtScreenCorner,
        keepDisplayOn = stored[KEEP_DISPLAY_ON] ?: defaults.keepDisplayOn,
    )
}

internal fun writeAppPreferences(value: AppPreferences, stored: MutablePreferences) {
    stored[THEME_MODE] = value.themeMode.name
    stored[COLOR_PALETTE] = value.colorPalette.name
    stored[TOGGLE_HOT_KEY] = value.toggleHotKey.name
    stored[CAPTURE_HOT_KEY] = value.captureHotKey.name
    stored[PAGE_CLICK_HOT_KEY] = value.pageClickHotKey.name
    stored[PAGE_SCROLL_HOT_KEY] = value.pageScrollHotKey.name
    stored[STOP_WHEN_POINTER_MOVES] = value.stopWhenPointerMoves
    stored[STOP_AT_SCREEN_CORNER] = value.stopAtScreenCorner
    stored[KEEP_DISPLAY_ON] = value.keepDisplayOn
}

private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default
