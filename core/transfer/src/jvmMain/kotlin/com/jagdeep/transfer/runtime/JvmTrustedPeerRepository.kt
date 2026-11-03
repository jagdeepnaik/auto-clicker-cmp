package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stores the complete trusted-peer set as one macOS Keychain secret, so updates are atomic. */
class JvmTrustedPeerRepository(
    private val secureStore: JvmSecureSecretStore,
    private val account: String = TRUSTED_PEERS_ACCOUNT,
) : TrustedPeerRepository, PairingRecoveryStore, AutoAcceptStore {
    private val lock = Mutex()
    private val loadedState = loadState()
    private val mutablePeers = MutableStateFlow(loadedState.peers)
    private val mutableRecoveries = MutableStateFlow(loadedState.recoveries)
    private val mutableAutoAccept = MutableStateFlow(loadedState.autoAccept)

    override val peers: StateFlow<List<TrustedPeer>> = mutablePeers
    override val pendingPairingRecoveries: StateFlow<List<PendingPairingRecovery>> = mutableRecoveries
    override val autoAcceptFingerprints: StateFlow<Set<String>> = mutableAutoAccept

    override suspend fun upsertVerified(peer: TrustedPeer) = lock.withLock {
        val updated = (mutablePeers.value.filterNot { it.deviceId == peer.deviceId } + peer)
            .sortedBy { it.displayName.lowercase() }
        persist(updated, mutableRecoveries.value)
        mutablePeers.value = updated
    }

    override suspend fun remove(deviceId: String) = lock.withLock {
        val updated = mutablePeers.value.filterNot { it.deviceId == deviceId }
        // A removed device never keeps auto-accept; pairing again starts with it off.
        val autoAccept = mutableAutoAccept.value.filterTo(mutableSetOf()) { fingerprint -> updated.any { it.certificateFingerprint == fingerprint } }
        persist(updated, mutableRecoveries.value, autoAccept)
        mutablePeers.value = updated
        mutableAutoAccept.value = autoAccept
    }

    override suspend fun setAutoAccept(fingerprint: String, enabled: Boolean): Boolean = lock.withLock {
        if (enabled && mutablePeers.value.none { it.certificateFingerprint == fingerprint }) return@withLock false
        val updated = if (enabled) mutableAutoAccept.value + fingerprint else mutableAutoAccept.value - fingerprint
        persist(mutablePeers.value, mutableRecoveries.value, updated)
        mutableAutoAccept.value = updated
        true
    }

    override suspend fun findByFingerprint(fingerprint: String): TrustedPeer? = lock.withLock {
        mutablePeers.value.firstOrNull { it.certificateFingerprint == fingerprint }
    }

    override suspend fun recordPendingPairingRecovery(record: PendingPairingRecovery) = lock.withLock {
        val updated = (mutableRecoveries.value.filterNot { it.peer.certificateFingerprint == record.peer.certificateFingerprint } + record)
            .sortedByDescending { it.recordedAtEpochMillis }
        persist(mutablePeers.value, updated)
        mutableRecoveries.value = updated
    }

    override suspend fun clearPendingPairingRecovery(peerFingerprint: String) = lock.withLock {
        val updated = mutableRecoveries.value.filterNot { it.peer.certificateFingerprint == peerFingerprint }
        persist(mutablePeers.value, updated)
        mutableRecoveries.value = updated
    }

    private fun loadState(): StoredState = secureStore.read(account)
        ?.let { encoded -> decode(Base64.getDecoder().decode(encoded)) }
        ?: StoredState()

    private fun persist(
        peers: List<TrustedPeer>,
        recoveries: List<PendingPairingRecovery>,
        autoAccept: Set<String> = mutableAutoAccept.value,
    ) {
        secureStore.write(account, Base64.getEncoder().encodeToString(encode(StoredState(peers, recoveries, autoAccept))))
    }

    private fun encode(state: StoredState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(FORMAT_VERSION)
            output.writeInt(state.peers.size)
            state.peers.forEach { output.writePeer(it) }
            output.writeInt(state.recoveries.size)
            state.recoveries.forEach { recovery ->
                output.writePeer(recovery.peer)
                output.writeUTF(recovery.sessionId)
                output.writeUTF(recovery.transcriptHash)
                output.writeLong(recovery.recordedAtEpochMillis)
            }
            output.writeInt(state.autoAccept.size)
            state.autoAccept.forEach(output::writeUTF)
        }
        bytes.toByteArray()
    }

    private fun DataOutputStream.writePeer(peer: TrustedPeer) {
        writeUTF(peer.deviceId)
        writeUTF(peer.displayName)
        writeUTF(peer.certificateFingerprint)
        writeInt(peer.certificateDer.size)
        write(peer.certificateDer)
    }

    private fun decode(bytes: ByteArray): StoredState = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        require(version in 1..FORMAT_VERSION) { "Unsupported trusted-peer storage." }
        val count = input.readInt()
        require(count in 0..MAX_TRUSTED_PEERS) { "Invalid trusted-peer count." }
        val peers = List(count) { input.readPeer() }
        val recoveries = if (version == 1) emptyList() else {
            val recoveryCount = input.readInt()
            require(recoveryCount in 0..MAX_PENDING_RECOVERIES) { "Invalid pairing recovery count." }
            List(recoveryCount) { PendingPairingRecovery(input.readPeer(), input.readUTF(), input.readUTF(), input.readLong()) }
        }
        // Version 3 adds auto-accept; earlier data loads with it empty (off for every device).
        val autoAccept = if (version < 3) emptySet() else {
            val autoAcceptCount = input.readInt()
            require(autoAcceptCount in 0..MAX_TRUSTED_PEERS) { "Invalid auto-accept count." }
            List(autoAcceptCount) { input.readUTF() }
                .onEach { require(ProtocolValidation.isSha256(it)) { "Invalid auto-accept entry." } }
                .filterTo(mutableSetOf()) { fingerprint -> peers.any { it.certificateFingerprint == fingerprint } }
        }
        require(input.available() == 0) { "Unexpected trusted-peer data." }
        StoredState(peers, recoveries, autoAccept)
    }

    private fun DataInputStream.readPeer(): TrustedPeer {
        val deviceId = readUTF()
        val displayName = readUTF()
        val fingerprint = readUTF()
        val certificateSize = readInt()
        require(certificateSize in 1..16_384) { "Invalid trusted-peer certificate." }
        return TrustedPeer(deviceId, displayName, fingerprint, ByteArray(certificateSize).also(::readFully))
    }

    private data class StoredState(
        val peers: List<TrustedPeer> = emptyList(),
        val recoveries: List<PendingPairingRecovery> = emptyList(),
        val autoAccept: Set<String> = emptySet(),
    )

    private companion object {
        const val TRUSTED_PEERS_ACCOUNT = "trusted-peers-v1"
        const val FORMAT_VERSION = 3
        const val MAX_TRUSTED_PEERS = 1_000
        const val MAX_PENDING_RECOVERIES = 1_000
    }
}
