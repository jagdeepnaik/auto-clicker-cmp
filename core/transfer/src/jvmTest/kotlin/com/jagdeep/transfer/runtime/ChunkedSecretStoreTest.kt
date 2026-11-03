package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkedSecretStoreTest {
    /** Mimics Windows Credential Manager: rejects any value above its per-credential limit. */
    private class LimitedStore(private val maxChars: Int) : JvmSecureSecretStore {
        val values = mutableMapOf<String, String>()
        override fun read(account: String) = values[account]
        override fun write(account: String, value: String) {
            check(value.length <= maxChars) { "Credential too large" }
            values[account] = value
        }
    }

    @Test
    fun `given a value larger than one credential when written then it round-trips through several`() {
        val backing = LimitedStore(maxChars = 1_280)
        val store = ChunkedSecretStore(backing)
        val big = "x".repeat(4_500) + "end"

        store.write("trusted-peers-v1", big)

        assertEquals(big, store.read("trusted-peers-v1"))
        assertTrue(backing.values.size > 1, "Large values must be split")
    }

    @Test
    fun `given a small value or one saved before chunking when read then it is returned unchanged`() {
        val backing = LimitedStore(maxChars = 1_280)
        backing.values["identity-pkcs12-password-v1"] = "legacy-password"
        val store = ChunkedSecretStore(backing)

        assertEquals("legacy-password", store.read("identity-pkcs12-password-v1"))
        store.write("small", "abc")
        assertEquals("abc", backing.values["small"])
    }

    @Test
    fun `given a value that shrinks when rewritten then the shorter value is read back`() {
        val store = ChunkedSecretStore(LimitedStore(maxChars = 1_280))
        store.write("k", "a".repeat(3_000))
        store.write("k", "b".repeat(1_500))
        assertEquals("b".repeat(1_500), store.read("k"))
        store.write("k", "short")
        assertEquals("short", store.read("k"))
    }
}
