package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.SyncTarget
import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferCapability
import com.jagdeep.transfer.protocol.TransferCapabilityNegotiator
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferProtocol
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock

sealed interface JvmTransferRuntimeState {
    data object Stopped : JvmTransferRuntimeState
    data class Listening(val endpoint: ListeningEndpoint) : JvmTransferRuntimeState
    data class Unavailable(val message: String) : JvmTransferRuntimeState
}

/** Desktop listener for one-file, explicitly approved transfers over strict TLS 1.3. */
class JvmTransferRuntime(
    private val scope: CoroutineScope,
    private val identity: JvmTlsIdentity,
    private val localPeer: PeerIdentity,
    private val trustedPeers: TrustedPeerRepository,
    private val approvals: TransferApprovalCoordinator,
    private val receiveDirectory: () -> Path?,
    private val onIncomingFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
    private val onIncomingProgress: (IncomingTransferActivity) -> Unit = {},
    /** Always called when an incoming connection ends, so progress UI never outlives it. */
    private val onIncomingEnded: (activityId: String) -> Unit = {},
    maxConcurrentIncomingTransfers: Int = DEFAULT_MAX_CONCURRENT_INCOMING_TRANSFERS,
) : AutoCloseable {
    init { require(maxConcurrentIncomingTransfers > 0) { "Incoming transfer limit must be positive." } }
    private val lock = Mutex()
    private val incomingTransfers = Semaphore(maxConcurrentIncomingTransfers)
    private var listener: JvmTlsListener? = null
    private var transport: JvmTlsTransport? = null
    private var acceptJob: Job? = null
    private val outgoingConnections = ConcurrentHashMap<String, JvmTlsConnection>()
    private val mutableState = MutableStateFlow<JvmTransferRuntimeState>(JvmTransferRuntimeState.Stopped)
    val state: StateFlow<JvmTransferRuntimeState> = mutableState

    suspend fun start(): JvmTransferRuntimeState = lock.withLock {
        listener?.let { return@withLock JvmTransferRuntimeState.Listening(it.endpoint) }
        val directory = receiveDirectory()
        if (directory != null && Files.isDirectory(directory)) {
            runCatching { JvmPartialTransferStore(directory).cleanupAbandoned() }
        }
        val peers = trustedPeers.peers.first()
        if (peers.isEmpty()) return@withLock unavailable("Pair a device before enabling secure transfers.")
        val certificates = runCatching { peers.map { decodeCertificate(it.certificateDer) } }
            .getOrElse { return@withLock unavailable("Saved trusted-device data is invalid. Pair the device again.") }
        val passwordBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val password = CharArray(passwordBytes.size) { index ->
            ('a'.code + ((passwordBytes[index].toInt() and 0xff) % 26)).toChar()
        }
        passwordBytes.fill(0)
        val createdTransport = JvmTlsTransport(JvmTrustedTlsContext.create(identity, password, certificates))
        password.fill('\u0000')
        val createdListener = createdTransport.listen()
        transport = createdTransport
        listener = createdListener
        acceptJob = scope.launch { acceptLoop(createdListener) }
        JvmTransferRuntimeState.Listening(createdListener.endpoint).also { mutableState.value = it }
    }

    suspend fun stop() = lock.withLock {
        acceptJob?.cancel(); acceptJob = null
        listener?.close(); listener = null; transport = null
        mutableState.value = JvmTransferRuntimeState.Stopped
    }

    /** Main-safe: blocking socket I/O runs on the IO dispatcher. */
    suspend fun sendFile(
        endpoint: PeerEndpoint,
        source: Path,
        onProgress: (TransferProgress) -> Unit = {},
        transferId: String = UUID.randomUUID().toString(),
        relativePath: String = requireNotNull(source.fileName).toString(),
    ): SingleFileTransferResult = withContext(Dispatchers.IO) { sendFileOnIo(endpoint, source, onProgress, transferId, relativePath) }

    private suspend fun sendFileOnIo(
        endpoint: PeerEndpoint,
        source: Path,
        onProgress: (TransferProgress) -> Unit,
        transferId: String,
        relativePath: String,
    ): SingleFileTransferResult {
        require(Files.isRegularFile(source)) { "The selected file is no longer available." }
        val activeTransport = requireNotNull(transport) { "Secure transfers are not ready. Pair a device and choose a receive folder first." }
        val connection = activeTransport.connect(endpoint)
        outgoingConnections[transferId] = connection
        return try {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-$transferId", localPeer))
            val remotePeer = connection.receiveHelloAndVerify(TrustedPeerAuthorizer(trustedPeers))
            trustedPeers.reconcilePairingRecovery(remotePeer.publicKeyFingerprint)
            if (shouldUseResumableManifests(localPeer, remotePeer)) {
                val manifest = JvmTransferManifestBuilder().build(transferId, source, relativePath)
                JvmResumableManifestTransfer(localPeer).send(
                    connection = connection,
                    manifest = manifest,
                    files = listOf(source),
                    onProgress = onProgress,
                    remotePeer = remotePeer,
                )
            } else {
                JvmSingleFileTransfer(localPeer).sendFile(
                    connection = connection,
                    transferId = transferId,
                    file = source,
                    onProgress = onProgress,
                    relativePath = relativePath,
                    remotePeer = remotePeer,
                )
            }
        } finally {
            outgoingConnections.remove(transferId)
            connection.close()
        }
    }

    /** Sends a folder or file selection as a single resumable manifest when both peers support it. */
    suspend fun sendFiles(
        endpoint: PeerEndpoint,
        files: List<JvmOutgoingTransferFile>,
        onProgress: (TransferProgress) -> Unit = {},
        transferId: String = UUID.randomUUID().toString(),
        sync: SyncTarget? = null,
    ): TransferBatchResult = withContext(Dispatchers.IO) { sendFilesOnIo(endpoint, files, onProgress, transferId, sync) }

    private suspend fun sendFilesOnIo(
        endpoint: PeerEndpoint,
        files: List<JvmOutgoingTransferFile>,
        onProgress: (TransferProgress) -> Unit,
        transferId: String,
        sync: SyncTarget? = null,
    ): TransferBatchResult {
        require(files.isNotEmpty() || sync?.remove?.isNotEmpty() == true) { "Select at least one file." }
        val label = files.firstOrNull()?.displayName ?: sync?.name
        files.forEach { require(Files.isRegularFile(it.source)) { "A selected file is no longer available." } }
        val activeTransport = requireNotNull(transport) { "Secure transfers are not ready. Pair a device and choose a receive folder first." }
        val connection = activeTransport.connect(endpoint)
        outgoingConnections[transferId] = connection
        return try {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-$transferId", localPeer))
            val remotePeer = connection.receiveHelloAndVerify(TrustedPeerAuthorizer(trustedPeers))
            trustedPeers.reconcilePairingRecovery(remotePeer.publicKeyFingerprint)
            if (!shouldUseResumableManifests(localPeer, remotePeer)) {
                if (shouldUseBatchManifests(localPeer, remotePeer)) {
                    val builder = JvmTransferManifestBuilder()
                    val manifest = TransferManifest(transferId, files.map { builder.build(transferId, it.source, it.relativePath).entries.single() }, sync)
                    return when (val result = JvmManifestTransfer(localPeer).send(connection, manifest, files.map { it.source }, onProgress, verifiedPeer = remotePeer)) {
                        is SingleFileTransferResult.Completed -> TransferBatchResult(files.size, null, null, result.peer)
                        is SingleFileTransferResult.Rejected -> TransferBatchResult(0, label, result, result.peer)
                    }
                }
                connection.close()
                // The one-file-per-connection fallback for very old peers can't carry a sync.
                if (sync != null) return TransferBatchResult(0, label, SingleFileTransferResult.Rejected(remotePeer, TransferApprovalMessages.SYNC_UNSUPPORTED), remotePeer)
                return files.fold(TransferBatchResult(0, null, null)) { batch, file ->
                    if (!batch.completed) batch else when (val result = sendFile(endpoint, file.source, onProgress, UUID.randomUUID().toString(), file.relativePath)) {
                        is SingleFileTransferResult.Completed -> batch.copy(completedCount = batch.completedCount + 1, peer = result.peer)
                        is SingleFileTransferResult.Rejected -> TransferBatchResult(batch.completedCount, file.displayName, result, result.peer)
                    }
                }
            }
            val builder = JvmTransferManifestBuilder()
            val manifest = TransferManifest(transferId, files.map { builder.build(transferId, it.source, it.relativePath).entries.single() }, sync)
            when (val result = JvmResumableManifestTransfer(localPeer).send(
                connection = connection,
                manifest = manifest,
                files = files.map(JvmOutgoingTransferFile::source),
                remotePeer = remotePeer,
                onProgress = onProgress,
            )) {
                is SingleFileTransferResult.Completed -> TransferBatchResult(files.size, null, null, result.peer)
                is SingleFileTransferResult.Rejected -> TransferBatchResult(0, label, result, result.peer)
            }
        } finally {
            outgoingConnections.remove(transferId)
            connection.close()
        }
    }

    fun cancelOutgoing(transferId: String): Boolean = outgoingConnections.remove(transferId)?.let {
        it.close()
        true
    } ?: false

    private suspend fun acceptLoop(activeListener: JvmTlsListener) {
        while (listener === activeListener) {
            runCatching { activeListener.accept() }.getOrNull()?.let { connection ->
                if (!incomingTransfers.tryAcquire()) {
                    connection.close()
                    return@let
                }
                scope.launch {
                    val activityId = UUID.randomUUID().toString()
                    var approvedManifest: TransferManifest? = null
                    var verifiedSender: PeerIdentity? = null
                    try {
                        val directory = receiveDirectory()
                            ?.also { folder -> runCatching { Files.createDirectories(folder) } }
                            ?.takeIf(Files::isDirectory) ?: return@launch
                        val remotePeer = connection.receiveHelloAndVerify(TrustedPeerAuthorizer(trustedPeers))
                        verifiedSender = remotePeer
                        trustedPeers.reconcilePairingRecovery(remotePeer.publicKeyFingerprint)
                        connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", localPeer))
                        val approver = IncomingTransferApprover { peer, manifest ->
                            (approvals.awaitDecision(IncomingTransferRequest("request-${manifest.transferId}", peer, manifest, directory.toString())) &&
                                Files.isDirectory(directory) && Files.isWritable(directory))
                                .also { accepted -> if (accepted) approvedManifest = manifest }
                        }
                        val onProgress: (TransferProgress) -> Unit = { progress ->
                            approvedManifest?.let { onIncomingProgress(IncomingTransferActivity(activityId, remotePeer, it, progress)) }
                        }
                        if (shouldUseResumableManifests(localPeer, remotePeer)) {
                            JvmResumableManifestTransfer(localPeer).receive(
                                connection = connection,
                                partialStore = JvmPartialTransferStore(directory),
                                approver = approver,
                                remotePeer = remotePeer,
                                onFinished = onIncomingFinished,
                                onProgress = onProgress,
                                syncRoot = directory,
                            )
                        } else if (shouldUseBatchManifests(localPeer, remotePeer)) {
                            JvmManifestTransfer(localPeer).receive(
                                connection = connection,
                                finalizer = JvmFileFinalizer(directory, FileConflictPolicy.RENAME),
                                approver = approver,
                                verifiedPeer = remotePeer,
                                onFinished = onIncomingFinished,
                                onProgress = onProgress,
                                syncRoot = directory,
                            )
                        } else {
                            JvmSingleFileTransfer(localPeer).receive(
                                connection = connection,
                                finalizer = JvmFileFinalizer(directory, FileConflictPolicy.RENAME),
                                approver = approver,
                                onFinished = onIncomingFinished,
                                remotePeer = remotePeer,
                                onProgress = onProgress,
                            )
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        // A receive that breaks mid-way (connection dropped, disk or lock error) must
                        // show up as failed on this device too, not just stop silently.
                        approvedManifest?.let { manifest ->
                            runCatching { onIncomingFinished(SingleFileTransferResult.Rejected(verifiedSender, "The transfer was interrupted (${failure::class.simpleName}). Send it again to resume."), manifest) }
                        }
                    } finally {
                        connection.close()
                        incomingTransfers.release()
                        onIncomingEnded(activityId)
                    }
                }
            }
        }
    }

    override fun close() {
        listener?.close(); acceptJob?.cancel()
        listener = null; acceptJob = null; transport = null
        mutableState.value = JvmTransferRuntimeState.Stopped
    }

    private fun unavailable(message: String) = JvmTransferRuntimeState.Unavailable(message).also { mutableState.value = it }
    private fun decodeCertificate(encoded: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
}

private const val DEFAULT_MAX_CONCURRENT_INCOMING_TRANSFERS = 2

internal fun shouldUseResumableManifests(localPeer: PeerIdentity, remotePeer: PeerIdentity): Boolean =
    TransferCapabilityNegotiator.supports(localPeer, remotePeer, TransferCapability.RESUMABLE_MANIFESTS)

internal fun shouldUseBatchManifests(localPeer: PeerIdentity, remotePeer: PeerIdentity): Boolean =
    TransferCapabilityNegotiator.supports(localPeer, remotePeer, TransferCapability.BATCH_MANIFESTS)

data class JvmOutgoingTransferFile(val source: Path, val displayName: String, val relativePath: String)
