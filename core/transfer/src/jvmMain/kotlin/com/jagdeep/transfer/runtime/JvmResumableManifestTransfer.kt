package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.ResumeEntry
import com.jagdeep.transfer.protocol.TransferErrorCode
import com.jagdeep.transfer.protocol.TransferManifest
import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Multi-file TLS transfer that resumes only prefixes independently verified by both peers. */
class JvmResumableManifestTransfer(private val localPeer: PeerIdentity) {
    suspend fun send(
        connection: JvmTlsConnection,
        manifest: TransferManifest,
        files: List<Path>,
        remotePeer: PeerIdentity? = null,
        onProgress: (TransferProgress) -> Unit = {},
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        require(manifest.entries.size == files.size) { "Every manifest entry must have one source file." }
        val totalBytes = manifest.entries.sumSizes()
        val verifiedPeer = remotePeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-${manifest.transferId}", localPeer))
            connection.receiveHelloAndVerify()
        }
        val requestId = "request-${manifest.transferId}"
        connection.sendMessage(ControlMessage.TransferRequest(TransferProtocol.CURRENT_VERSION, requestId, manifest))
        val decision = connection.receiveMessage() as? ControlMessage.TransferDecision
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer sent an unexpected response to the transfer request.")
        if (!decision.accepted || decision.requestId != requestId || decision.transferId != manifest.transferId) {
            return@withContext SingleFileTransferResult.Rejected(verifiedPeer, decision.reason ?: TransferApprovalMessages.NOT_ACCEPTED)
        }
        val resumeRequest = connection.receiveMessage() as? ControlMessage.ResumeRequest
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer did not provide resume negotiation.")
        if (resumeRequest.transferId != manifest.transferId) return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer resume request did not match the transfer.")
        val acceptedResumes = acceptedSourcePrefixes(manifest, files, resumeRequest.entries)
        connection.sendMessage(ControlMessage.ResumeDecision(TransferProtocol.CURRENT_VERSION, "resume-decision-${manifest.transferId}", manifest.transferId, acceptedResumes))
        val offsets = acceptedResumes.associateBy(ResumeEntry::relativePath)
        val startNanos = System.nanoTime()
        var transferred = acceptedResumes.sumOf(ResumeEntry::verifiedBytes)
        onProgress(TransferProgress(transferred, totalBytes, 0))
        manifest.entries.zip(files).forEach { (entry, file) ->
            val offset = offsets[entry.relativePath]?.verifiedBytes ?: 0L
            Files.newInputStream(file).use { input ->
                input.skipExactly(offset)
                input.copyExactlyTo(connection.dataOutput(), entry.sizeBytes - offset) { current ->
                    onProgress(TransferProgress(transferred + current, totalBytes, startNanos.elapsedMillis()))
                }
            }
            transferred = Math.addExact(transferred, entry.sizeBytes - offset)
        }
        connection.sendMessage(ControlMessage.TransferComplete(TransferProtocol.CURRENT_VERSION, "complete-${manifest.transferId}", manifest.transferId, transferred))
        val result = connection.receiveMessage() as? ControlMessage.TransferResult
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer did not confirm transfer completion.")
        if (result.transferId == manifest.transferId && result.completed) SingleFileTransferResult.Completed(verifiedPeer, transferred)
        else SingleFileTransferResult.Rejected(verifiedPeer, result.message ?: "Peer could not verify the received files.")
    }

    suspend fun receive(
        connection: JvmTlsConnection,
        partialStore: JvmPartialTransferStore,
        approver: IncomingTransferApprover,
        remotePeer: PeerIdentity? = null,
        onFinished: (SingleFileTransferResult, TransferManifest) -> Unit = { _, _ -> },
        onProgress: (TransferProgress) -> Unit = {},
        /** Receive folder that sync transfers are confined to; null means this receiver declines syncs. */
        syncRoot: java.nio.file.Path? = null,
    ): SingleFileTransferResult = withContext(Dispatchers.IO) {
        val verifiedPeer = remotePeer ?: run {
            connection.sendMessage(ControlMessage.Hello(TransferProtocol.CURRENT_VERSION, "hello-receiver", localPeer))
            connection.receiveHelloAndVerify()
        }
        val request = connection.receiveMessage() as? ControlMessage.TransferRequest
            ?: return@withContext SingleFileTransferResult.Rejected(verifiedPeer, "Peer did not send a transfer request.")
        val isSync = request.manifest.sync != null
        val accepted = (!isSync || syncRoot != null) && approver.approve(verifiedPeer, request.manifest)
        val declineMessage = if (isSync && syncRoot == null) TransferApprovalMessages.SYNC_UNSUPPORTED else TransferApprovalMessages.NOT_ACCEPTED
        connection.sendMessage(ControlMessage.TransferDecision(TransferProtocol.CURRENT_VERSION, request.requestId, request.manifest.transferId, accepted, if (accepted) null else declineMessage))
        if (!accepted) return@withContext SingleFileTransferResult.Rejected(verifiedPeer, declineMessage)
        // A sync writes into its own folder, where replaced files were first moved to trash.
        val partialStore = if (isSync) JvmPartialTransferStore(JvmSyncDestination.prepare(requireNotNull(syncRoot), request.manifest)) else partialStore
        val partials = request.manifest.entries.associate { entry -> entry.relativePath to partialStore.open(request.manifest.transferId, entry) }
        val offers = partials.values.mapNotNull(JvmPartialTransfer::resumeEntry)
        connection.sendMessage(ControlMessage.ResumeRequest(TransferProtocol.CURRENT_VERSION, "resume-${request.manifest.transferId}", request.manifest.transferId, offers))
        val decision = connection.receiveMessage() as? ControlMessage.ResumeDecision
            ?: return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer did not respond to resume negotiation.")
        if (decision.transferId != request.manifest.transferId) return@withContext SingleFileTransferResult.Rejected(remotePeer, "Peer resume response did not match the transfer.")
        val acceptedResumes = ResumeNegotiator.acceptedEntries(request.manifest, decision.acceptedEntries)
            .filter { acceptedEntry -> partials[acceptedEntry.relativePath]?.resumeEntry() == acceptedEntry }
        val offsets = acceptedResumes.associateBy(ResumeEntry::relativePath)
        partials.forEach { (path, partial) -> if (partial.resumeEntry() != null && path !in offsets) partial.discard() }
        val totalBytes = request.manifest.entries.sumSizes()
        val startNanos = System.nanoTime()
        var transferred = acceptedResumes.sumOf(ResumeEntry::verifiedBytes)
        var failure: String? = null
        onProgress(TransferProgress(transferred, totalBytes, 0))
        request.manifest.entries.forEach { entry ->
            val partial = partials[entry.relativePath] ?: error("Missing partial transfer state.")
            val offset = offsets[entry.relativePath]?.verifiedBytes ?: 0L
            val body = LimitedInputStream(connection.dataInput(), entry.sizeBytes - offset)
            // Report progress while the file is written, not only before and after it.
            val result = partial.appendAndFinalize(body) { appended ->
                onProgress(TransferProgress(transferred + appended, totalBytes, startNanos.elapsedMillis()))
            }
            body.drain()
            if (result is FileFinalizationResult.Rejected && failure == null) failure = result.message
            transferred = Math.addExact(transferred, entry.sizeBytes - offset)
        }
        val completion = connection.receiveMessage() as? ControlMessage.TransferComplete
        val completed = failure == null && completion?.transferId == request.manifest.transferId && completion.bytesTransferred == totalBytes
        val outcome = ControlMessage.TransferResult(
            TransferProtocol.CURRENT_VERSION, "result-${request.manifest.transferId}", request.manifest.transferId,
            completed, if (completed) null else TransferErrorCode.INTEGRITY_MISMATCH,
            if (completed) null else failure ?: "Completion metadata did not match the request.",
        )
        connection.sendMessage(outcome)
        val transferResult = if (completed) SingleFileTransferResult.Completed(verifiedPeer, totalBytes)
        else SingleFileTransferResult.Rejected(verifiedPeer, outcome.message.orEmpty())
        onFinished(transferResult, request.manifest)
        transferResult
    }

    private fun acceptedSourcePrefixes(manifest: TransferManifest, files: List<Path>, requested: List<ResumeEntry>): List<ResumeEntry> {
        val requestedEntries = ResumeNegotiator.acceptedEntries(manifest, requested)
        val sourceByPath = manifest.entries.zip(files).associate { (entry, file) -> entry.relativePath to file }
        return requestedEntries.filter { candidate -> sourceByPath[candidate.relativePath]?.prefixHash(candidate.verifiedBytes) == candidate.prefixSha256 }
    }
}

private fun InputStream.skipExactly(byteCount: Long) {
    var remaining = byteCount
    val buffer = ByteArray(32 * 1024)
    while (remaining > 0) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
        require(count >= 0) { "Source ended before its negotiated resume offset." }
        if (count > 0) remaining -= count
    }
}

private fun Path.prefixHash(length: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(this).use { input ->
        val buffer = ByteArray(256 * 1024)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            require(count >= 0) { "Source ended before its negotiated resume offset." }
            if (count > 0) { digest.update(buffer, 0, count); remaining -= count }
        }
    }
    return digest.digest().joinToString(separator = "") { "%02x".format(it) }
}

private fun List<com.jagdeep.transfer.protocol.TransferManifestEntry>.sumSizes(): Long = fold(0L) { total, entry -> Math.addExact(total, entry.sizeBytes) }
private fun Long.elapsedMillis(): Long = ((System.nanoTime() - this) / 1_000_000L).coerceAtLeast(0)
