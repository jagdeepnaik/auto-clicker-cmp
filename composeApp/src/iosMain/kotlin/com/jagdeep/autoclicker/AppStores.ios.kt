package com.jagdeep.autoclicker

import com.jagdeep.persistence.preferences.InMemoryPreferencesStore
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.preferences.AppPreferences

// iOS can't click in other apps, so nothing here is worth keeping between launches yet.
actual fun createAppPreferencesStore(): PreferencesStore<AppPreferences> = InMemoryPreferencesStore(AppPreferences())

actual fun createDocumentStore(name: String): PreferencesStore<String> = InMemoryPreferencesStore("")
