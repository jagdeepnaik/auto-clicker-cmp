package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class JvmNearbyPairingTest {
    private class Peers : TrustedPeerRepository {
        override val peers = MutableStateFlow<List<TrustedPeer>>(emptyList())
        override suspend fun upsertVerified(peer: TrustedPeer) { peers.value += peer }
        override suspend fun remove(deviceId: String) = Unit
        override suspend fun findByFingerprint(fingerprint: String) = peers.value.firstOrNull { it.certificateFingerprint == fingerprint }
    }

    @Test
    fun `given two desktops over real mutual TLS when both confirm the shown code then each trusts the other's real certificate`() = runBlocking {
        val directory = Files.createTempDirectory("zynk-nearby")
        val password = "test-password".toCharArray()
        val mac = JvmPeerIdentityStore(directory.resolve("mac.p12")).create(password)
        val pc = JvmPeerIdentityStore(directory.resolve("pc.p12")).create(password)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pcPeers = Peers(); val macPeers = Peers(); val approvals = PairingApprovalCoordinator()
        val listener = JvmNearbyPairingListener(
            scope, pc,
            { LocalPairingIdentity("desktop-" + pc.certificateFingerprint.take(24), "Office PC", pc.certificateFingerprint) },
            pcPeers, approvals,
        )
        try {
            listener.start()
            val port = listener.port.first { it != null }!!
            var shown = ""
            scope.launch {
                val request = approvals.pending.first { it.isNotEmpty() }.single()
                while (shown.isEmpty()) kotlinx.coroutines.delay(10)
                assertEquals(shown, request.verificationCode)
                approvals.decide(request.sessionId, true)
            }
            val result = jvmPairWithNearby(
                mac,
                LocalPairingIdentity("desktop-" + mac.certificateFingerprint.take(24), "Mac", mac.certificateFingerprint),
                macPeers, "127.0.0.1", port,
            ) { name, code -> assertEquals("Office PC", name); shown = code; true }
            assertIs<PairingConnectionResult.Paired>(result)
            assertEquals(pc.certificateFingerprint, macPeers.peers.value.single().certificateFingerprint)
            pcPeers.peers.first { it.isNotEmpty() }
            assertEquals(mac.certificateFingerprint, pcPeers.peers.value.single().certificateFingerprint)
        } finally {
            listener.close()
            scope.cancel()
            directory.toFile().deleteRecursively()
        }
    }
}
