package com.jagdeep.transfer.runtime

import android.net.Uri
import android.content.Context
import com.jagdeep.transfer.protocol.PeerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

sealed interface AndroidTransferRuntimeState {
    data object Stopped : AndroidTransferRuntimeState
    data class Listening(val endpoint: ListeningEndpoint) : AndroidTransferRuntimeState
    data class Unavailable(val message: String) : AndroidTransferRuntimeState
}

/**
 * App-scoped Android listener. It accepts only the certificates present when it is started;
 * restart it after pairing/unpairing so its strict trust store is refreshed.
 */
class AndroidTransferRuntime(
    context: Context,
    private val scope: CoroutineScope,
    private val identity: AndroidTlsIdentity,
    private val localPeer: PeerIdentity,
    private val trustedPeers: TrustedPeerRepository,
    private val approvals: TransferApprovalCoordinator,
    private val receiveFolder: () -> String?,
    private val onIncomingFinished: (SingleFileTransferResult, com.jagdeep.transfer.protocol.TransferManifest) -> Unit = { _, _ -> },
    private val onIncomingProgress: (IncomingTransferActivity) -> Unit = {},
    /** Always called when an incoming connection ends, so progress UI never outlives it. */
    private val onIncomingEnded: (activityId: String) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Mutex()
    private var listener: AndroidTlsListener? = null
    private var transport: AndroidTlsTransport? = null
    private var acceptJob: Job? = null
    private val outgoingConnections = ConcurrentHashMap<String, AndroidTlsConnection>()
    private val mutableState = MutableStateFlow<AndroidTransferRuntimeState>(AndroidTransferRuntimeState.Stopped)

    val state: StateFlow<AndroidTransferRuntimeState> = mutableState

    suspend fun start(): AndroidTransferRuntimeState = lock.withLock {
        listener?.let { return@withLock AndroidTransferRuntimeState.Listening(it.endpoint) }
        if (localPeer.publicKeyFingerprint != identity.certificateFingerprint) {
            return@withLock unavailable("The local secure identity is not configured correctly.")
        }
        val peers = trustedPeers.peers.first()
        if (peers.isEmpty()) return@withLock unavailable("Pair a device before enabling secure transfers.")
        val certificates = runCatching { peers.map(TrustedPeer::certificateDer).map(::decodeCertificate) }
            .getOrElse { return@withLock unavailable("Saved trusted-device data is invalid. Pair the device again.") }
        val createdTransport = AndroidTlsTransport(AndroidTrustedTlsContext.create(identity, certificates))
        val created = createdTransport.listen()
        transport = createdTransport
        listener = created
        acceptJob = scope.launch { acceptLoop(created) }
        AndroidTransferRuntimeState.Listening(created.endpoint).also { mutableState.value = it }
    }

    suspend fun stop() = lock.withLock {
        acceptJob?.cancel()
        acceptJob = null
        listener?.close()
        listener = null
        transport = null
        mutableState.value = AndroidTransferRuntimeState.Stopped
    }

    override fun close() {
        listener?.close()
        acceptJob?.cancel()
        listener = null
        transport = null
        acceptJob = null
        mutableState.value = AndroidTransferRuntimeState.Stopped
    }

    private suspend fun acceptLoop(activeListener: AndroidTlsListener) {
        while (listener === activeListener) {
            runCatching { activeListener.accept() }.getOrNull()?.let { connection ->
                scope.launch {
                    val activityId = UUID.randomUUID().toString()
                    var accepted: Pair<PeerIdentity, com.jagdeep.transfer.protocol.TransferManifest>? = null
                    try {
                        val reference = receiveFolder() ?: return@launch
                        if (!canReceiveIntoAndroidTree(appContext, reference)) return@launch
                        val folder = Uri.parse(reference)
                        AndroidIncomingTransfer(localPeer).receive(
                            connection = connection,
                            authorizer = TrustedPeerAuthorizer(trustedPeers),
                            approvals = approvals,
                            finalizer = if (reference == ANDROID_DOWNLOADS_APP_DESTINATION) AndroidMediaStoreFileFinalizer(appContext) else AndroidSafFileFinalizerFromUri(folder),
                            destination = reference,
                            isDestinationAvailable = { canReceiveIntoAndroidTree(appContext, reference) },
                            onFinished = onIncomingFinished,
                            onProgress = { progress ->
                                accepted?.let { (peer, manifest) -> onIncomingProgress(IncomingTransferActivity(activityId, peer, manifest, progress)) }
                            },
                            onAuthenticated = { peer -> trustedPeers.reconcilePairingRecovery(peer.publicKeyFingerprint) },
                            onAccepted = { peer, manifest -> accepted = peer to manifest },
                        )
                    } finally {
                        connection.close()
                        onIncomingEnded(activityId)
                    }
                }
            }
        }
    }

    private fun unavailable(message: String): AndroidTransferRuntimeState.Unavailable =
        AndroidTransferRuntimeState.Unavailable(message).also { mutableState.value = it }

    private fun decodeCertificate(encoded: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate

    private fun AndroidSafFileFinalizerFromUri(folder: Uri): AndroidSafFileFinalizer =
        AndroidSafFileFinalizer(appContext, folder)

    suspend fun sendFile(
        endpoint: PeerEndpoint,
        sourceReference: String,
        relativePath: String,
        onProgress: (TransferProgress) -> Unit = {},
        transferId: String = UUID.randomUUID().toString(),
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val activeTransport = requireNotNull(transport) { "Secure transfers are not ready. Pair a device and choose a receive folder first." }
        val connection = activeTransport.connect(endpoint)
        outgoingConnections[transferId] = connection
        try {
            AndroidOutgoingTransfer(localPeer, AndroidTransferContentAccess(appContext)).sendFile(
                connection = connection,
                trustedPeers = TrustedPeerAuthorizer(trustedPeers),
                transferId = transferId,
                sourceReference = sourceReference,
                relativePath = relativePath,
                onProgress = onProgress,
                onAuthenticated = { peer -> trustedPeers.reconcilePairingRecovery(peer.publicKeyFingerprint) },
            )
        } finally {
            outgoingConnections.remove(transferId)
            connection.close()
        }
    }

    suspend fun sendFiles(
        endpoint: PeerEndpoint,
        files: List<OutgoingTransferFile>,
        onProgress: (TransferProgress) -> Unit = {},
        transferId: String = UUID.randomUUID().toString(),
        sync: com.jagdeep.transfer.protocol.SyncTarget? = null,
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val activeTransport = requireNotNull(transport) { "Secure transfers are not ready. Pair a device and choose a receive folder first." }
        val connection = activeTransport.connect(endpoint)
        outgoingConnections[transferId] = connection
        try {
            AndroidOutgoingTransfer(localPeer, AndroidTransferContentAccess(appContext)).sendFiles(
                connection, TrustedPeerAuthorizer(trustedPeers), transferId, files, onProgress,
                onAuthenticated = { peer -> trustedPeers.reconcilePairingRecovery(peer.publicKeyFingerprint) },
                sync = sync,
            )
        } finally { outgoingConnections.remove(transferId); connection.close() }
    }

    fun cancelOutgoing(transferId: String): Boolean = outgoingConnections.remove(transferId)?.let {
        it.close()
        true
    } ?: false
}
