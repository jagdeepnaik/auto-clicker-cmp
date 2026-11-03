package com.jagdeep.persistence.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class PreferencesStoreTest {
    @Test
    fun `in memory store retains the most recently saved model`() = runBlocking {
        val store = InMemoryPreferencesStore(initial = "initial")

        store.save("updated")

        assertEquals("updated", store.data.first())
    }
}
