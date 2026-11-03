package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.SyncTarget
import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.ProtocolValidation
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/** Builds a manifest and streams a user-selected Android SAF file without retaining it in memory. */
class AndroidOutgoingTransfer(
    private val localPeer: PeerIdentity,
    private val contentAccess: TransferContentAccess,
) {
    /** Streams a complete selection over one authenticated connection after one approval. */
    suspend fun sendFiles(
        connection: AndroidTlsConnection,
        trustedPeers: TrustedPeerAuthorizer,
        transferId: String,
        files: List<OutgoingTransferFile>,
        onProgress: (TransferProgress) -> Unit = {},
        onAuthenticated: suspend (PeerIdentity) -> Unit = {},
        sync: SyncTarget? = null,
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        require(files.isNotEmpty() || sync?.remove?.isNotEmpty() == true) { "Select at least one file." }
        val manifest = TransferManifest(transferId, files.map { buildManifest(transferId, it.sourceReference, it.relativePath).entries.single() }, sync)
        val totalBytes = manifest.entries.fold(0L) { total, entry -> Math.addExact(total, entry.sizeBytes) }
        connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-$transferId", localPeer))
        val remotePeer = connection.receiveHelloAndAuthorize(trustedPeers)
        onAuthenticated(remotePeer)
        val requestId = "request-$transferId"
        connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, requestId, manifest))
        val decision = connection.receiveMessage() as? ControlMessage.TransferDecision
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer sent an unexpected response to the transfer request.")
        if (decision.requestId != requestId || decision.transferId != transferId || !decision.accepted) {
            return@withContext SingleFileTransferResult.Rejected(remotePeer, decision.reason ?: TransferApprovalMessages.NOT_ACCEPTED)
        }
        val startNanos = System.nanoTime()
        var completedBytes = 0L
        onProgress(TransferProgress(0, totalBytes, 0))
        files.zip(manifest.entries).forEach { (file, entry) ->
            val content = openContent(file.sourceReference)
            try {
                content.source.copyExactlyTo(connection.dataOutput(), entry.sizeBytes) { copied ->
                    onProgress(TransferProgress(completedBytes + copied, totalBytes, startNanos.elapsedMillis()))
                }
            } finally { content.source.close() }
            completedBytes = Math.addExact(completedBytes, entry.sizeBytes)
        }
        connection.sendMessage(ControlMessage.TransferComplete(TransferProtocol.CURRENT_VERSION, "complete-$transferId", transferId, completedBytes))
        val result = connection.receiveMessage() as? ControlMessage.TransferResult
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not confirm transfer completion.")
        if (result.transferId == transferId && result.completed) SingleFileTransferResult.Completed(remotePeer, completedBytes)
        else SingleFileTransferResult.Rejected(remotePeer, result.message ?: "Peer could not verify the transfer.")
    }

    suspend fun sendFile(
        connection: AndroidTlsConnection,
        trustedPeers: TrustedPeerAuthorizer,
        transferId: String,
        sourceReference: String,
        relativePath: String,
        onProgress: (TransferProgress) -> Unit = {},
        onAuthenticated: suspend (PeerIdentity) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val manifest = buildManifest(transferId, sourceReference, relativePath)
        val entry = manifest.entries.single()
        connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-$transferId", localPeer))
        val remotePeer = connection.receiveHelloAndAuthorize(trustedPeers)
        onAuthenticated(remotePeer)
        val requestId = "request-$transferId"
        connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, requestId, manifest))
        val decision = connection.receiveMessage() as? ControlMessage.TransferDecision
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer sent an unexpected response to the transfer request.")
        if (decision.requestId != requestId || decision.transferId != transferId || !decision.accepted) {
            return@withContext SingleFileTransferResult.Rejected(remotePeer, decision.reason ?: TransferApprovalMessages.NOT_ACCEPTED)
        }

        val startNanos = System.nanoTime()
        onProgress(TransferProgress(0, entry.sizeBytes, 0))
        val content = openContent(sourceReference)
        val bytesTransferred = try {
            content.source.copyExactlyTo(connection.dataOutput(), entry.sizeBytes) { copied ->
                onProgress(TransferProgress(copied, entry.sizeBytes, startNanos.elapsedMillis()))
            }
        } finally {
            content.source.close()
        }
        connection.sendMessage(
            ControlMessage.TransferComplete(
                TransferProtocol.CURRENT_VERSION,
                "complete-$transferId",
                transferId,
                bytesTransferred,
            ),
        )
        val result = connection.receiveMessage() as? ControlMessage.TransferResult
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not confirm transfer completion.")
        if (result.transferId != transferId || !result.completed) {
            SingleFileTransferResult.Rejected(remotePeer, result.message ?: "Peer could not verify the received file.")
        } else {
            SingleFileTransferResult.Completed(remotePeer, bytesTransferred)
        }
    }

    private suspend fun buildManifest(transferId: String, sourceReference: String, relativePath: String): TransferManifest {
        val content = openContent(sourceReference)
        try {
            require(ProtocolValidation.isSafeRelativePath(relativePath)) { "The selected file path is not supported." }
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var size = 0L
            while (true) {
                val count = content.source.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size = Math.addExact(size, count.toLong())
                digest.update(buffer, 0, count)
            }
            return TransferManifest(
                transferId,
                listOf(TransferManifestEntry(relativePath, size, digest.digest().toLowercaseHex())),
            )
        } finally {
            content.source.close()
        }
    }

    private suspend fun openContent(sourceReference: String): OpenTransferContent =
        requireNotNull(contentAccess.openForRead(sourceReference)) { "The selected file is no longer readable." }
}

private fun TransferBinarySource.copyExactlyTo(
    destination: java.io.OutputStream,
    expectedBytes: Long,
    onBytesCopied: (Long) -> Unit,
): Long {
    val buffer = ByteArray(BUFFER_SIZE)
    var copied = 0L
    while (copied < expectedBytes) {
        val count = read(buffer)
        if (count < 0) throw IllegalArgumentException("Source ended before its declared size.")
        if (count == 0) continue
        val remaining = expectedBytes - copied
        require(count.toLong() <= remaining) { "Source exceeds its declared size." }
        destination.write(buffer, 0, count)
        copied += count
        onBytesCopied(copied)
    }
    require(read(buffer) < 0) { "Source exceeds its declared size." }
    destination.flush()
    return copied
}

private fun ByteArray.toLowercaseHex(): String = joinToString(separator = "") { "%02x".format(it) }

private fun Long.elapsedMillis(): Long = ((System.nanoTime() - this) / 1_000_000L).coerceAtLeast(0)

private const val BUFFER_SIZE = 512 * 1024
