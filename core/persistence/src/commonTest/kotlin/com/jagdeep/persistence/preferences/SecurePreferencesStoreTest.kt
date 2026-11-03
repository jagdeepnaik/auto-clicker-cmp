package com.jagdeep.persistence.preferences

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class SecurePreferencesStoreTest {
    @Test
    fun `secure store contract supports save load and explicit clear`() = runBlocking {
        val store = FakeSecurePreferencesStore<String>()

        store.save("test-secret")
        assertEquals("test-secret", store.data.first())

        store.clear()
        assertNull(store.data.first())
    }
}

private class FakeSecurePreferencesStore<T> : SecurePreferencesStore<T> {
    private val mutableData = MutableStateFlow<T?>(null)
    override val data = mutableData

    override suspend fun save(value: T) {
        mutableData.value = value
    }

    override suspend fun clear() {
        mutableData.value = null
    }
}
