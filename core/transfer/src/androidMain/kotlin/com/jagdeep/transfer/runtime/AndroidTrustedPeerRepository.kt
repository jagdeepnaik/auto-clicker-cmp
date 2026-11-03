package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Android secure implementation: a single authenticated encrypted blob is stored in private
 * preferences, while its AES-256 key remains in Android Keystore.
 */
class AndroidTrustedPeerRepository(context: Context) : TrustedPeerRepository, PairingRecoveryStore, AutoAcceptStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
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

    private fun loadState(): StoredState = runCatching {
        val encoded = preferences.getString(TRUSTED_PEERS_KEY, null) ?: return StoredState()
        decode(decrypt(Base64.decode(encoded, Base64.NO_WRAP)))
    }.getOrDefault(StoredState())

    private fun persist(
        peers: List<TrustedPeer>,
        recoveries: List<PendingPairingRecovery>,
        autoAccept: Set<String> = mutableAutoAccept.value,
    ) {
        val encrypted = encrypt(encode(StoredState(peers, recoveries, autoAccept)))
        check(preferences.edit().putString(TRUSTED_PEERS_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) {
            "Could not securely save trusted devices."
        }
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun encrypt(plainText: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, encryptionKey()) }
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plainText)
        return ByteArray(1 + iv.size + cipherText.size).also { output ->
            output[0] = iv.size.toByte()
            iv.copyInto(output, 1)
            cipherText.copyInto(output, 1 + iv.size)
        }
    }

    private fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size > 1) { "Invalid trusted-peer storage." }
        val ivSize = encrypted[0].toInt() and 0xff
        require(ivSize in 12..16 && encrypted.size > 1 + ivSize) { "Invalid trusted-peer storage." }
        return Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, encrypted.copyOfRange(1, 1 + ivSize)))
        }.doFinal(encrypted, 1 + ivSize, encrypted.size - 1 - ivSize)
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
        writeUTF(peer.deviceId); writeUTF(peer.displayName); writeUTF(peer.certificateFingerprint)
        writeInt(peer.certificateDer.size); write(peer.certificateDer)
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
        val deviceId = readUTF(); val displayName = readUTF(); val fingerprint = readUTF(); val certificateSize = readInt()
        require(certificateSize in 1..16_384) { "Invalid trusted-peer certificate." }
        return TrustedPeer(deviceId, displayName, fingerprint, ByteArray(certificateSize).also(::readFully))
    }

    private data class StoredState(
        val peers: List<TrustedPeer> = emptyList(),
        val recoveries: List<PendingPairingRecovery> = emptyList(),
        val autoAccept: Set<String> = emptySet(),
    )

    private companion object {
        const val PREFERENCES_NAME = "zynk_secure_trusted_peers"
        const val TRUSTED_PEERS_KEY = "trusted_peers_v1"
        const val KEY_ALIAS = "zynk_trusted_peers_aes"
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 3
        const val MAX_TRUSTED_PEERS = 1_000
        const val MAX_PENDING_RECOVERIES = 1_000
    }
}
