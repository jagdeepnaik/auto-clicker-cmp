package com.jagdeep.transfer.runtime

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class NearbyPairingTest {
    private val crypto = NearbyPairingCrypto({ MessageDigest.getInstance("SHA-256").digest(it) }, { ByteArray(it).also(SecureRandom()::nextBytes) })
    private fun fp(c: Char) = c.toString().repeat(64)
    private val mac = LocalPairingIdentity("desktop-" + fp('a').take(24), "Mac", fp('a'))
    private val pc = LocalPairingIdentity("desktop-" + fp('b').take(24), "Office PC", fp('b'))

    /** Two connected channels; each reports the other side's certificate as observed by TLS. */
    private fun link(aSees: String, bSees: String): Pair<PairingChannel, PairingChannel> {
        val ab = Channel<PairingWireMessage>(Channel.UNLIMITED)
        val ba = Channel<PairingWireMessage>(Channel.UNLIMITED)
        fun end(out: Channel<PairingWireMessage>, inbox: Channel<PairingWireMessage>, sees: String) = object : PairingChannel {
            override val observedPeerFingerprint = sees
            override val observedPeerCertificateDer = byteArrayOf(1, 2, 3)
            override suspend fun send(message: PairingWireMessage) {
                // Every message crosses the real wire codec, so its validation is exercised too.
                out.send(PairingWireMessageCodec.decode(PairingWireMessageCodec.encode(message)))
            }
            override suspend fun receive(): PairingWireMessage = inbox.receive()
        }
        return end(ab, ba, aSees) to end(ba, ab, bSees)
    }

    private class Peers : TrustedPeerRepository, PairingRecoveryStore {
        override val peers = MutableStateFlow<List<TrustedPeer>>(emptyList())
        override val pendingPairingRecoveries = MutableStateFlow<List<PendingPairingRecovery>>(emptyList())
        override suspend fun upsertVerified(peer: TrustedPeer) { peers.value = peers.value.filterNot { it.deviceId == peer.deviceId } + peer }
        override suspend fun remove(deviceId: String) = Unit
        override suspend fun findByFingerprint(fingerprint: String) = peers.value.firstOrNull { it.certificateFingerprint == fingerprint }
        override suspend fun recordPendingPairingRecovery(record: PendingPairingRecovery) { pendingPairingRecoveries.value += record }
        override suspend fun clearPendingPairingRecovery(peerFingerprint: String) = Unit
    }

    private fun responder(peers: Peers, approvals: PairingApprovalCoordinator, self: LocalPairingIdentity = pc) =
        NearbyPairingResponder(crypto, self, peers, approvals, nowEpochMillis = { 1_000L })

    @Test
    fun `given both users confirm the same code when pairing nearby then both store the other's observed certificate`() = runBlocking {
        val (macEnd, pcEnd) = link(aSees = pc.fingerprint, bSees = mac.fingerprint)
        val macPeers = Peers(); val pcPeers = Peers(); val approvals = PairingApprovalCoordinator()
        var macCode = ""
        coroutineScope {
            val responded = async { responder(pcPeers, approvals).handle(pcEnd) }
            launch {
                val request = approvals.pending.first { it.isNotEmpty() }.single()
                assertEquals("Mac", request.peer.displayName)
                // The responder's user sees the same code the initiator's user sees.
                while (macCode.isEmpty()) kotlinx.coroutines.yield()
                assertEquals(macCode, request.verificationCode)
                approvals.decide(request.sessionId, true)
            }
            val result = NearbyPairingInitiator(crypto, mac, macPeers).pair(macEnd) { name, code ->
                assertEquals("Office PC", name)
                assertTrue(code.length == 6 && code.all(Char::isDigit))
                macCode = code
                true
            }
            assertIs<PairingConnectionResult.Paired>(result)
            assertTrue(responded.await())
        }
        assertEquals(listOf(pc.fingerprint), macPeers.peers.value.map { it.certificateFingerprint })
        assertEquals(listOf(mac.fingerprint), pcPeers.peers.value.map { it.certificateFingerprint })
        assertEquals(1, pcPeers.pendingPairingRecoveries.value.size)
    }

    @Test
    fun `given the responder declines when pairing nearby then neither side stores anything`() = runBlocking {
        val (macEnd, pcEnd) = link(pc.fingerprint, mac.fingerprint)
        val macPeers = Peers(); val pcPeers = Peers(); val approvals = PairingApprovalCoordinator()
        coroutineScope {
            val responded = async { responder(pcPeers, approvals).handle(pcEnd) }
            launch { approvals.decide(approvals.pending.first { it.isNotEmpty() }.single().sessionId, false) }
            val result = NearbyPairingInitiator(crypto, mac, macPeers).pair(macEnd) { _, _ -> true }
            assertIs<PairingConnectionResult.Rejected>(result)
            assertFalse(responded.await())
        }
        assertEquals(emptyList(), macPeers.peers.value)
        assertEquals(emptyList(), pcPeers.peers.value)
    }

    @Test
    fun `given a machine in the middle with its own certificate on each leg when pairing then the two codes differ`() = runBlocking {
        // Each real device observes the attacker's certificate, so each hashes a different fingerprint pair.
        val attacker = fp('e')
        val (macEnd, attackerToMac) = link(aSees = attacker, bSees = mac.fingerprint)
        val (attackerToPc, pcEnd) = link(aSees = pc.fingerprint, bSees = attacker)
        val approvals = PairingApprovalCoordinator()
        var macCode = ""
        var pcCode = ""
        coroutineScope {
            launch { responder(Peers(), approvals).handle(pcEnd) }
            // A relay forwarding claims unchanged is rejected outright (claims must match the observed
            // certificate), so the attacker rewrites device IDs to its own and relays everything else.
            val attackerId = "desktop-" + attacker.take(24)
            fun disguise(m: PairingWireMessage) = when (m) {
                is PairingWireMessage.SasHello -> m.copy(deviceId = attackerId)
                is PairingWireMessage.SasNonce -> m.copy(deviceId = attackerId)
                else -> m
            }
            launch { while (true) attackerToPc.send(disguise(attackerToMac.receive())) }
            launch { while (true) attackerToMac.send(disguise(attackerToPc.receive())) }
            launch {
                val request = approvals.pending.first { it.isNotEmpty() }.single()
                pcCode = request.verificationCode
                approvals.decide(request.sessionId, false)
            }
            NearbyPairingInitiator(crypto, mac, Peers()).pair(macEnd) { _, code ->
                macCode = code
                // Both users look at their screens at the same time; wait until the PC shows its code too.
                while (pcCode.isEmpty()) kotlinx.coroutines.yield()
                false
            }
            coroutineContext.cancelChildren()
        }
        assertTrue(macCode.isNotEmpty() && pcCode.isNotEmpty())
        assertNotEquals(macCode, pcCode, "A relay with its own certificate must produce different codes on the two devices")
    }

    @Test
    fun `given a relay that forwards identity claims unchanged when pairing then both devices reject it before showing a code`() = runBlocking {
        val attacker = fp('e')
        val (macEnd, attackerToMac) = link(aSees = attacker, bSees = mac.fingerprint)
        val (attackerToPc, pcEnd) = link(aSees = pc.fingerprint, bSees = attacker)
        val approvals = PairingApprovalCoordinator()
        var codeShown = false
        coroutineScope {
            val responded = async { responder(Peers(), approvals).handle(pcEnd) }
            launch { while (true) attackerToPc.send(attackerToMac.receive()) }
            launch { while (true) attackerToMac.send(attackerToPc.receive()) }
            val result = NearbyPairingInitiator(crypto, mac, Peers()).pair(macEnd) { _, _ -> codeShown = true; true }
            assertIs<PairingConnectionResult.Rejected>(result)
            assertFalse(responded.await())
            coroutineContext.cancelChildren()
        }
        assertFalse(codeShown)
        assertEquals(emptyList(), approvals.pending.value)
    }

    @Test
    fun `given a reveal that does not open the commitment when responding then the session is rejected before any prompt`() = runBlocking {
        val (initiator, pcEnd) = link(pc.fingerprint, mac.fingerprint)
        val approvals = PairingApprovalCoordinator()
        coroutineScope {
            val responded = async { responder(Peers(), approvals).handle(pcEnd) }
            val sessionId = crypto.newSessionId()
            initiator.send(PairingWireMessage.SasHello(PairingProtocol.CURRENT_VERSION, sessionId, mac.deviceId, mac.displayName, crypto.commitment(sessionId, crypto.newNonce())))
            assertIs<PairingWireMessage.SasNonce>(initiator.receive())
            // A different nonce than the one committed to.
            initiator.send(PairingWireMessage.SasReveal(PairingProtocol.CURRENT_VERSION, sessionId, "ab".repeat(32)))
            assertIs<PairingWireMessage.Rejection>(initiator.receive())
            assertFalse(responded.await())
        }
        assertEquals(emptyList(), approvals.pending.value)
    }

    @Test
    fun `given a device id that does not belong to the observed certificate when pairing then it is rejected`() = runBlocking {
        val (initiator, pcEnd) = link(pc.fingerprint, mac.fingerprint)
        coroutineScope {
            val responded = async { responder(Peers(), PairingApprovalCoordinator()).handle(pcEnd) }
            val sessionId = crypto.newSessionId()
            initiator.send(PairingWireMessage.SasHello(PairingProtocol.CURRENT_VERSION, sessionId, "desktop-" + fp('c').take(24), "Impostor", crypto.commitment(sessionId, crypto.newNonce())))
            assertIs<PairingWireMessage.Rejection>(initiator.receive())
            assertFalse(responded.await())
        }
    }

    @Test
    fun `given identical inputs when computing codes then they match, and any changed input changes the code`() {
        val nI = ByteArray(32) { 1 }; val nR = ByteArray(32) { 2 }
        val base = crypto.code(crypto.transcript("s", fp('a'), fp('b'), nI, nR))
        assertEquals(base, crypto.code(crypto.transcript("s", fp('a'), fp('b'), nI, nR)))
        assertNotEquals(crypto.transcript("s", fp('a'), fp('b'), nI, nR), crypto.transcript("s", fp('a'), fp('c'), nI, nR))
        assertNotEquals(crypto.transcript("s", fp('a'), fp('b'), nI, nR), crypto.transcript("t", fp('a'), fp('b'), nI, nR))
        assertNotEquals(crypto.transcript("s", fp('a'), fp('b'), nI, nR), crypto.transcript("s", fp('a'), fp('b'), nR, nI))
    }

    @Test
    fun `given three failures from one address when limiting then it is refused until the cool-down passes`() = runBlocking {
        var now = 0L
        val limiter = PairingRateLimiter({ now }, maxFailures = 3, cooldownMillis = 600_000)
        repeat(3) { assertTrue(limiter.allows("10.0.0.5")); limiter.record("10.0.0.5", succeeded = false) }
        assertFalse(limiter.allows("10.0.0.5"))
        assertTrue(limiter.allows("10.0.0.6"))
        now = 600_000
        assertTrue(limiter.allows("10.0.0.5"))
    }
}

private fun kotlin.coroutines.CoroutineContext.cancelChildren() = this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }

