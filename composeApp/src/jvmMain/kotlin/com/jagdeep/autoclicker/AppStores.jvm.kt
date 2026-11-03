package com.jagdeep.autoclicker

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.jagdeep.persistence.preferences.DataStorePreferencesStore
import com.jagdeep.persistence.preferences.PreferencesStore
import com.jagdeep.shared.preferences.AppPreferences
import java.io.File

private val storeDirectory: File by lazy { File(System.getProperty("user.home"), ".autoclicker").also(File::mkdirs) }

actual fun createAppPreferencesStore(): PreferencesStore<AppPreferences> = DataStorePreferencesStore(
    dataStore = PreferenceDataStoreFactory.create { File(storeDirectory, APP_PREFERENCES_FILE) },
    read = ::readAppPreferences,
    write = ::writeAppPreferences,
)

actual fun createDocumentStore(name: String): PreferencesStore<String> = DataStorePreferencesStore(
    dataStore = PreferenceDataStoreFactory.create { File(storeDirectory, documentFileName(name)) },
    read = { it[DOCUMENT_KEY].orEmpty() },
    write = { value, preferences -> preferences[DOCUMENT_KEY] = value },
)
