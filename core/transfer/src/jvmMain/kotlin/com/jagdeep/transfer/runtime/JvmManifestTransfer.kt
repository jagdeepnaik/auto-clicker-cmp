package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferErrorCode
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** Streams every entry of an approved manifest sequentially over a single TLS connection. */
class JvmManifestTransfer(private val localPeer: PeerIdentity) {
    suspend fun sendDirectory(
        connection: JvmTlsConnection,
        transferId: String,
        directory: Path,
        manifestBuilder: JvmTransferManifestBuilder = JvmTransferManifestBuilder(),
        onProgress: (TransferProgress) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val manifest = manifestBuilder.buildDirectory(transferId, directory)
        val root = directory.toAbsolutePath().normalize()
        send(connection, manifest, manifest.entries.map { root.resolve(it.relativePath) }, onProgress)
    }

    suspend fun send(
        connection: JvmTlsConnection,
        manifest: TransferManifest,
        files: List<Path>,
        onProgress: (TransferProgress) -> Unit = {},
        verifiedPeer: PeerIdentity? = null,
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        require(manifest.entries.size == files.size) { "Every manifest entry must have one source file." }
        val totalBytes = manifest.entries.sumSizes()
        // A runtime that already exchanged Hello passes the verified peer; repeating it desynchronizes Android peers.
        val remotePeer = verifiedPeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-${manifest.transferId}", localPeer))
            connection.receiveHelloAndVerify()
        }
        val requestId = "request-${manifest.transferId}"
        connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, requestId, manifest))
        val decision = connection.receiveMessage() as? ControlMessage.TransferDecision
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer sent an unexpected response to the transfer request.")
        if (!decision.accepted || decision.requestId != requestId || decision.transferId != manifest.transferId) {
            return@withContext SingleFileTransferResult.Rejected(remotePeer, decision.reason ?: TransferApprovalMessages.NOT_ACCEPTED)
        }
        val startNanos = System.nanoTime()
        var sent = 0L
        onProgress(TransferProgress(0, totalBytes, 0))
        manifest.entries.zip(files).forEach { (entry, file) ->
            Files.newInputStream(file).use { input ->
                input.copyExactlyTo(connection.dataOutput(), entry.sizeBytes) { entryBytes ->
                    onProgress(TransferProgress(sent + entryBytes, totalBytes, startNanos.elapsedMillis()))
                }
            }
            sent = Math.addExact(sent, entry.sizeBytes)
        }
        connection.sendMessage(ControlMessage.TransferComplete(TransferProtocol.CURRENT_VERSION, "complete-${manifest.transferId}", manifest.transferId, sent))
        val result = connection.receiveMessage() as? ControlMessage.TransferResult
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not confirm transfer completion.")
        return@withContext if (result.transferId == manifest.transferId && result.completed) {
            SingleFileTransferResult.Completed(remotePeer, sent)
        } else SingleFileTransferResult.Rejected(remotePeer, result.message ?: "Peer could not verify the received files.")
    }

    suspend fun receive(
        connection: JvmTlsConnection,
        finalizer: JvmFileFinalizer,
        approver: IncomingTransferApprover,
        onProgress: (TransferProgress) -> Unit = {},
        verifiedPeer: PeerIdentity? = null,
        onFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
        /** Receive folder that sync transfers are confined to; null means this receiver declines syncs. */
        syncRoot: Path? = null,
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val remotePeer = verifiedPeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", localPeer))
            connection.receiveHelloAndVerify()
        }
        val request = connection.receiveMessage() as? ControlMessage.TransferRequest
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not send a transfer request.")
        val isSync = request.manifest.sync != null
        val accepted = (!isSync || syncRoot != null) && approver.approve(remotePeer, request.manifest)
        val declineMessage = if (isSync && syncRoot == null) TransferApprovalMessages.SYNC_UNSUPPORTED else TransferApprovalMessages.NOT_ACCEPTED
        connection.sendMessage(ControlMessage.TransferDecision(TransferProtocol.CURRENT_VERSION, request.requestId, request.manifest.transferId, accepted, if (accepted) null else declineMessage))
        if (!accepted) {
            val rejected = SingleFileTransferResult.Rejected(remotePeer, declineMessage)
            onFinished(rejected, request.manifest)
            return@withContext rejected
        }
        // A sync writes into its own folder, where replaced files were first moved to trash.
        val finalizer = if (isSync) JvmFileFinalizer(JvmSyncDestination.prepare(requireNotNull(syncRoot), request.manifest), FileConflictPolicy.REJECT) else finalizer
        val totalBytes = request.manifest.entries.sumSizes()
        val startNanos = System.nanoTime()
        var received = 0L
        var failure: String? = null
        onProgress(TransferProgress(0, totalBytes, 0))
        request.manifest.entries.forEach { entry ->
            val body = LimitedInputStream(connection.dataInput(), entry.sizeBytes)
            val finalized = finalizer.writeVerified(entry, body) { entryBytes ->
                onProgress(TransferProgress(received + entryBytes, totalBytes, startNanos.elapsedMillis()))
            }
            body.drain()
            if (finalized is FileFinalizationResult.Rejected && failure == null) failure = finalized.message
            received = Math.addExact(received, entry.sizeBytes)
        }
        val completion = connection.receiveMessage() as? ControlMessage.TransferComplete
        val completed = failure == null && completion?.transferId == request.manifest.transferId && completion.bytesTransferred == totalBytes
        val outcome = ControlMessage.TransferResult(
            TransferProtocol.CURRENT_VERSION,
            "result-${request.manifest.transferId}",
            request.manifest.transferId,
            completed,
            if (completed) null else TransferErrorCode.INTEGRITY_MISMATCH,
            if (completed) null else failure ?: "Completion metadata did not match the request.",
        )
        connection.sendMessage(outcome)
        val transferResult = if (completed) SingleFileTransferResult.Completed(remotePeer, totalBytes)
        else SingleFileTransferResult.Rejected(remotePeer, outcome.message.orEmpty())
        onFinished(transferResult, request.manifest)
        transferResult
    }
}

private fun List<com.jagdeep.transfer.protocol.TransferManifestEntry>.sumSizes(): Long = fold(0L) { total, entry -> Math.addExact(total, entry.sizeBytes) }
private fun Long.elapsedMillis(): Long = ((System.nanoTime() - this) / 1_000_000L).coerceAtLeast(0)
