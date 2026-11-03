package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferErrorCode
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/** Receives an explicitly approved manifest over an already mutually authenticated TLS connection. */
class AndroidIncomingTransfer(private val localPeer: PeerIdentity) {
    suspend fun receive(
        connection: AndroidTlsConnection,
        authorizer: TrustedPeerAuthorizer,
        approvals: TransferApprovalCoordinator,
        finalizer: AndroidFileFinalizer,
        destination: String,
        isDestinationAvailable: () -> Boolean,
        onFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
        onProgress: (TransferProgress) -> Unit = {},
        onAuthenticated: suspend (PeerIdentity) -> Unit = {},
        onAccepted: (PeerIdentity, TransferManifest) -> Unit = { _, _ -> },
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", localPeer))
        val remotePeer = connection.receiveHelloAndAuthorize(authorizer)
        onAuthenticated(remotePeer)
        val request = connection.receiveMessage() as? ControlMessage.TransferRequest
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not send a transfer request.")
        // Receiving folder syncs needs replace-with-trash in MediaStore/SAF, which Android does not
        // support yet: decline without prompting so the sender gets a clear reason.
        val isSync = request.manifest.sync != null
        val accepted = !isSync && approvals.awaitDecision(IncomingTransferRequest(request.requestId, remotePeer, request.manifest, destination)) &&
            isDestinationAvailable()
        connection.sendMessage(
            ControlMessage.TransferDecision(
                TransferProtocol.CURRENT_VERSION,
                request.requestId,
                request.manifest.transferId,
                accepted,
                if (accepted) null else if (isSync) TransferApprovalMessages.SYNC_UNSUPPORTED else TransferApprovalMessages.NOT_ACCEPTED,
            ),
        )
        if (!accepted) {
            val rejected = SingleFileTransferResult.Rejected(remotePeer, if (isSync) TransferApprovalMessages.SYNC_UNSUPPORTED else TransferApprovalMessages.NOT_ACCEPTED)
            onFinished(rejected, request.manifest)
            return@withContext rejected
        }

        onAccepted(remotePeer, request.manifest)
        val totalBytes = request.manifest.entries.fold(0L) { total, entry -> Math.addExact(total, entry.sizeBytes) }
        val startNanos = System.nanoTime()
        var receivedBytes = 0L
        var failure: String? = null
        onProgress(TransferProgress(0, totalBytes, 0))
        request.manifest.entries.forEach { entry ->
            val body = AndroidLimitedInputStream(connection.dataInput(), entry.sizeBytes)
            val finalized = finalizer.writeVerified(entry, body) { written ->
                onProgress(TransferProgress(receivedBytes + written, totalBytes, startNanos.elapsedMillis()))
            }
            body.drain()
            if (finalized is AndroidFileFinalizationResult.Rejected && failure == null) {
                failure = finalized.message
            }
            receivedBytes = Math.addExact(receivedBytes, entry.sizeBytes)
        }
        val completion = connection.receiveMessage() as? ControlMessage.TransferComplete
        val completed = failure == null &&
            completion?.transferId == request.manifest.transferId && completion.bytesTransferred == totalBytes
        val result = ControlMessage.TransferResult(
            TransferProtocol.CURRENT_VERSION,
            "result-${request.manifest.transferId}",
            request.manifest.transferId,
            completed,
            if (completed) null else TransferErrorCode.INTEGRITY_MISMATCH,
            if (completed) null else failure ?: "Completion metadata did not match the request.",
        )
        connection.sendMessage(result)
        val transferResult = if (completed) SingleFileTransferResult.Completed(remotePeer, totalBytes)
        else SingleFileTransferResult.Rejected(remotePeer, result.message.orEmpty())
        onFinished(transferResult, request.manifest)
        transferResult
    }

}

private class AndroidLimitedInputStream(private val delegate: InputStream, private var remaining: Long) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        return delegate.read().also { if (it >= 0) remaining-- }
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        return delegate.read(bytes, offset, minOf(length.toLong(), remaining).toInt()).also { if (it > 0) remaining -= it }
    }

    fun drain() {
        val buffer = ByteArray(32 * 1024)
        while (read(buffer) >= 0) {
            // Consume exactly the declared entry bytes before the next control frame.
        }
    }

    override fun close() = Unit
}

private fun Long.elapsedMillis(): Long = ((System.nanoTime() - this) / 1_000_000L).coerceAtLeast(0)
