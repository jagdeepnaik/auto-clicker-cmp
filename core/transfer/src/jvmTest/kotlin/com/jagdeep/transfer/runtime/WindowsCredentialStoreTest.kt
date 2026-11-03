package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises real Windows DPAPI (skipped elsewhere): the store desktop pairing uses on Windows.
 * Runs in CI on a Windows runner.
 */
class WindowsCredentialStoreTest {
    private val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    @Test
    fun `given Windows when secrets are protected with DPAPI then they round-trip, including large ones`() {
        if (!isWindows) return
        val directory = Files.createTempDirectory("zynk-dpapi")
        try {
            val store = WindowsDpapiSecretStore(directory)
            store.write("identity-pkcs12-password-v1", "pw-123")
            assertEquals("pw-123", store.read("identity-pkcs12-password-v1"))
            val large = "y".repeat(20_000)
            store.write("trusted-peers-v1", large)
            assertEquals(large, WindowsDpapiSecretStore(directory).read("trusted-peers-v1"))
            assertEquals(null, store.read("missing"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given Windows when the desktop identity and trusted devices use DPAPI then they load again`() {
        if (!isWindows) return
        val directory = Files.createTempDirectory("zynk-win-identity")
        try {
            val store = WindowsDpapiSecretStore(directory.resolve("secure"))
            store.write("identity-pkcs12-password-v1", "pw-" + System.nanoTime())
            val password = store.read("identity-pkcs12-password-v1")!!.toCharArray()
            val identity = JvmPeerIdentityStore(directory.resolve("identity.p12")).loadOrCreate(password)
            assertEquals(identity.certificateFingerprint, JvmPeerIdentityStore(directory.resolve("identity.p12")).loadOrCreate(password).certificateFingerprint)

            val peers = JvmTrustedPeerRepository(store)
            kotlinx.coroutines.runBlocking {
                peers.upsertVerified(TrustedPeer("desktop-a", "Mac", "a".repeat(64), ByteArray(900) { 1 }))
                peers.upsertVerified(TrustedPeer("android-b", "Phone", "b".repeat(64), ByteArray(900) { 2 }))
            }
            assertEquals(2, JvmTrustedPeerRepository(store).peers.value.size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
