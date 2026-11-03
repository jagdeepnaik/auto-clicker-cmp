package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.TransferErrorCode
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferManifestEntry
import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Explicit receiver decision for an incoming transfer request. */
fun interface IncomingTransferApprover {
    suspend fun approve(peer: PeerIdentity, manifest: TransferManifest): Boolean
}

/**
 * One transfer per TLS connection. Control messages are length-framed JSON; the declared file
 * bytes are streamed raw between the approval and completion control messages.
 */
class JvmSingleFileTransfer(
    private val localPeer: PeerIdentity,
) {
    /** Opens and streams a selected desktop file after computing its manifest hash. */
    suspend fun sendFile(
        connection: JvmTlsConnection,
        transferId: String,
        file: Path,
        manifestBuilder: JvmTransferManifestBuilder = JvmTransferManifestBuilder(),
        authorizer: TrustedPeerAuthorizer? = null,
        relativePath: String? = null,
        remotePeer: PeerIdentity? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val manifest = manifestBuilder.build(transferId, file, relativePath ?: requireNotNull(file.fileName).toString())
        Files.newInputStream(file).use { source ->
            send(
                connection = connection,
                manifest = manifest,
                source = source,
                authorizer = authorizer,
                remotePeer = remotePeer,
                onProgress = onProgress,
            )
        }
    }

    suspend fun send(
        connection: JvmTlsConnection,
        manifest: TransferManifest,
        source: InputStream,
        authorizer: TrustedPeerAuthorizer? = null,
        remotePeer: PeerIdentity? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val entry = manifest.requireOneEntry()
        val verifiedPeer = remotePeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, helloRequestId(manifest), localPeer))
            connection.receiveHelloAndVerify(authorizer)
        }
        val requestId = "request-${manifest.transferId}"
        connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, requestId, manifest))
        val decision = connection.receiveMessage() as? ControlMessage.TransferDecision
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer sent an unexpected response to the transfer request.")
        if (decision.requestId != requestId || decision.transferId != manifest.transferId) {
            return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer response did not match the transfer request.")
        }
        if (!decision.accepted) {
            return@withContext SingleFileTransferResult.Rejected(verifiedPeer, decision.reason ?: TransferApprovalMessages.NOT_ACCEPTED)
        }

        val startNanos = System.nanoTime()
        onProgress(TransferProgress(0, entry.sizeBytes, 0))
        val bytesTransferred = source.copyExactlyTo(connection.dataOutput(), entry.sizeBytes) { copied ->
            onProgress(TransferProgress(copied, entry.sizeBytes, startNanos.elapsedMillis()))
        }
        connection.sendMessage(
            ControlMessage.TransferComplete(
                protocolVersion = TransferProtocol.CURRENT_VERSION,
                requestId = "complete-${manifest.transferId}",
                transferId = manifest.transferId,
                bytesTransferred = bytesTransferred,
            ),
        )
        val result = connection.receiveMessage() as? ControlMessage.TransferResult
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer did not confirm transfer completion.")
        if (result.transferId != manifest.transferId || !result.completed) {
            return@withContext SingleFileTransferResult.Rejected(verifiedPeer, result.message ?: "Peer could not verify the received file.")
        }
        SingleFileTransferResult.Completed(verifiedPeer, bytesTransferred)
    }

    suspend fun receive(
        connection: JvmTlsConnection,
        finalizer: JvmFileFinalizer,
        approver: IncomingTransferApprover,
        authorizer: TrustedPeerAuthorizer? = null,
        onFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
        remotePeer: PeerIdentity? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val verifiedPeer = remotePeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", localPeer))
            connection.receiveHelloAndVerify(authorizer)
        }
        val request = connection.receiveMessage() as? ControlMessage.TransferRequest
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer did not send a transfer request.")
        val entry = try {
            request.manifest.requireOneEntry()
        } catch (failure: IllegalArgumentException) {
            connection.sendMessage(
                ControlMessage.TransferDecision(
                    TransferProtocol.CURRENT_VERSION,
                    request.requestId,
                    request.manifest.transferId,
                    accepted = false,
                    reason = failure.message,
                ),
            )
            return@withContext SingleFileTransferResult.Rejected(verifiedPeer, failure.message.orEmpty())
        }
        val accepted = approver.approve(verifiedPeer, request.manifest)
        connection.sendMessage(
            ControlMessage.TransferDecision(
                TransferProtocol.CURRENT_VERSION,
                request.requestId,
                request.manifest.transferId,
                accepted = accepted,
                reason = if (accepted) null else TransferApprovalMessages.NOT_ACCEPTED,
            ),
        )
        if (!accepted) {
            val rejected = SingleFileTransferResult.Rejected(verifiedPeer, TransferApprovalMessages.NOT_ACCEPTED)
            onFinished(rejected, request.manifest)
            return@withContext rejected
        }

        val fileBody = LimitedInputStream(connection.dataInput(), entry.sizeBytes)
        val startNanos = System.nanoTime()
        onProgress(TransferProgress(0, entry.sizeBytes, 0))
        val finalized = finalizer.writeVerified(entry, fileBody) { written ->
            onProgress(TransferProgress(written, entry.sizeBytes, startNanos.elapsedMillis()))
        }
        fileBody.drain()
        val completion = connection.receiveMessage() as? ControlMessage.TransferComplete
        val completed = finalized as? FileFinalizationResult.Completed
        val completionMatches = completion?.transferId == request.manifest.transferId &&
            completion.bytesTransferred == entry.sizeBytes
        val outcome = if (completed != null && completionMatches) {
            ControlMessage.TransferResult(
                TransferProtocol.CURRENT_VERSION,
                "result-${request.manifest.transferId}",
                request.manifest.transferId,
                completed = true,
            )
        } else {
            ControlMessage.TransferResult(
                TransferProtocol.CURRENT_VERSION,
                "result-${request.manifest.transferId}",
                request.manifest.transferId,
                completed = false,
                errorCode = TransferErrorCode.INTEGRITY_MISMATCH,
                message = (finalized as? FileFinalizationResult.Rejected)?.message ?: "Completion metadata did not match the request.",
            )
        }
        connection.sendMessage(outcome)
        val transferResult = if (outcome.completed) SingleFileTransferResult.Completed(verifiedPeer, entry.sizeBytes)
        else SingleFileTransferResult.Rejected(verifiedPeer, outcome.message.orEmpty())
        onFinished(transferResult, request.manifest)
        transferResult
    }

    private fun TransferManifest.requireOneEntry(): TransferManifestEntry {
        require(entries.size == 1) { "Desktop MVP supports exactly one file per transfer." }
        return entries.single()
    }

    private fun helloRequestId(manifest: TransferManifest) = "hello-${manifest.transferId}"
}

private fun Long.elapsedMillis(): Long = ((System.nanoTime() - this) / NANOS_PER_MILLISECOND).coerceAtLeast(0)

private const val NANOS_PER_MILLISECOND = 1_000_000L
