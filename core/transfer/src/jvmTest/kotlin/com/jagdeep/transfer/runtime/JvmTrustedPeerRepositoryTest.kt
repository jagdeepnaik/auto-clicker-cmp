package com.jagdeep.transfer.runtime

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class JvmTrustedPeerRepositoryTest {
    @Test
    fun `given a verified peer when repository is recreated then peer is restored from secure store`() = runTest {
        val directory = Files.createTempDirectory("zynk-trusted-peer")
        try {
            val identity = JvmPeerIdentityStore(directory.resolve("peer.p12")).create("password".toCharArray())
            val peer = TrustedPeer(
                deviceId = "desktop-peer",
                displayName = "Mac",
                certificateFingerprint = identity.certificateFingerprint,
                certificateDer = identity.certificate.encoded,
            )
            val secureStore = FakeSecureSecretStore()

            JvmTrustedPeerRepository(secureStore).upsertVerified(peer)
            val restored = JvmTrustedPeerRepository(secureStore)

            val restoredPeer = restored.peers.value.single()
            assertEquals(peer.deviceId, restoredPeer.deviceId)
            assertEquals(peer.displayName, restoredPeer.displayName)
            assertEquals(peer.certificateFingerprint, restoredPeer.certificateFingerprint)
            assertContentEquals(peer.certificateDer, restoredPeer.certificateDer)
            assertEquals(peer.certificateFingerprint, restored.findByFingerprint(peer.certificateFingerprint)?.certificateFingerprint)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given a peer when removed then secure state no longer contains it`() = runTest {
        val directory = Files.createTempDirectory("zynk-trusted-peer")
        try {
            val identity = JvmPeerIdentityStore(directory.resolve("peer.p12")).create("password".toCharArray())
            val peer = TrustedPeer("desktop-peer", "Mac", identity.certificateFingerprint, identity.certificate.encoded)
            val repository = JvmTrustedPeerRepository(FakeSecureSecretStore())

            repository.upsertVerified(peer)
            repository.remove(peer.deviceId)

            assertEquals(emptyList(), repository.peers.value)
            assertNull(repository.findByFingerprint(peer.certificateFingerprint))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `given a final acknowledgement may be lost when repository restarts then repair record is retained`() = runTest {
        val directory = Files.createTempDirectory("zynk-pairing-recovery")
        try {
            val identity = JvmPeerIdentityStore(directory.resolve("peer.p12")).create("password".toCharArray())
            val peer = TrustedPeer("desktop-peer", "Mac", identity.certificateFingerprint, identity.certificate.encoded)
            val store = FakeSecureSecretStore()
            val repository = JvmTrustedPeerRepository(store)
            repository.recordPendingPairingRecovery(PendingPairingRecovery(peer, "session-1", "a".repeat(64), 1L))

            val restored = JvmTrustedPeerRepository(store)

            assertEquals("session-1", restored.pendingPairingRecoveries.value.single().sessionId)
            assertEquals(peer.certificateFingerprint, restored.pendingPairingRecoveries.value.single().peer.certificateFingerprint)
            restored.reconcilePairingRecovery(peer.certificateFingerprint)
            assertEquals(emptyList(), restored.pendingPairingRecoveries.value)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun peer(seed: Char, id: String = "desktop-$seed") = TrustedPeer(id, "Device $seed", seed.toString().repeat(64), byteArrayOf(1, 2, 3))

    @Test
    fun `given an allowed device when the repository restarts then auto-accept is restored, and only paired devices can be allowed`() = runTest {
        val store = FakeSecureSecretStore()
        val repository = JvmTrustedPeerRepository(store)
        repository.upsertVerified(peer('a'))

        assertTrue(repository.setAutoAccept("a".repeat(64), true))
        assertFalse(repository.setAutoAccept("b".repeat(64), true), "An unpaired certificate must never be allowed")

        assertEquals(setOf("a".repeat(64)), JvmTrustedPeerRepository(store).autoAcceptFingerprints.value)
    }

    @Test
    fun `given an allowed device when it is removed and paired again then auto-accept starts off`() = runTest {
        val repository = JvmTrustedPeerRepository(FakeSecureSecretStore())
        repository.upsertVerified(peer('a'))
        repository.setAutoAccept("a".repeat(64), true)

        repository.remove("desktop-a")
        assertEquals(emptySet(), repository.autoAcceptFingerprints.value)

        repository.upsertVerified(peer('a'))
        assertEquals(emptySet(), repository.autoAcceptFingerprints.value)
        assertFalse(repository.allowsAutomaticAccept(masterEnabled = true, senderFingerprint = "a".repeat(64)))
    }

    @Test
    fun `given pairings saved before auto-accept existed when loaded then they are kept and nothing is auto-accepted`() = runTest {
        // Format version 2: peers and recoveries only.
        val v2 = java.io.ByteArrayOutputStream().also { bytes ->
            java.io.DataOutputStream(bytes).use { out ->
                out.writeInt(2); out.writeInt(1)
                out.writeUTF("desktop-a"); out.writeUTF("Device a"); out.writeUTF("a".repeat(64)); out.writeInt(3); out.write(byteArrayOf(1, 2, 3))
                out.writeInt(0)
            }
        }.toByteArray()
        val store = FakeSecureSecretStore().apply { write("trusted-peers-v1", java.util.Base64.getEncoder().encodeToString(v2)) }

        val repository = JvmTrustedPeerRepository(store)
        assertEquals(listOf("desktop-a"), repository.peers.value.map { it.deviceId })
        assertEquals(emptySet(), repository.autoAcceptFingerprints.value)
    }

    @Test
    fun `given the policy inputs when deciding then only master on, allowed and still paired skips the question`() = runTest {
        val repository = JvmTrustedPeerRepository(FakeSecureSecretStore())
        repository.upsertVerified(peer('a'))
        repository.upsertVerified(peer('b'))
        repository.setAutoAccept("a".repeat(64), true)

        assertTrue(repository.allowsAutomaticAccept(masterEnabled = true, senderFingerprint = "a".repeat(64)))
        assertFalse(repository.allowsAutomaticAccept(masterEnabled = false, senderFingerprint = "a".repeat(64)), "Master switch off")
        assertFalse(repository.allowsAutomaticAccept(masterEnabled = true, senderFingerprint = "b".repeat(64)), "Paired but not allowed")
        assertFalse(repository.allowsAutomaticAccept(masterEnabled = true, senderFingerprint = "c".repeat(64)), "Unknown device")
    }

    private class FakeSecureSecretStore : JvmSecureSecretStore {
        private val values = mutableMapOf<String, String>()
        override fun read(account: String): String? = values[account]
        override fun write(account: String, value: String) {
            values[account] = value
        }
    }
}
