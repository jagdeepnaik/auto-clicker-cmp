package com.jagdeep.transfer.runtime

/** App-shell boundary used by features to request one secure outgoing file transfer. */
interface TransferSender {
    suspend fun sendFile(
        endpoint: PeerEndpoint,
        sourceReference: String,
        displayName: String,
        sizeBytes: Long,
        relativePath: String = displayName,
    ): SingleFileTransferResult

    /**
     * Sends a related file selection as one logical request when the platform supports it.
     * The default preserves interoperability by falling back to the established file path.
     */
    suspend fun sendFiles(endpoint: PeerEndpoint, files: List<OutgoingTransferFile>): TransferBatchResult {
        var completed = 0
        for (file in files) {
            when (val result = sendFile(endpoint, file.sourceReference, file.displayName, file.sizeBytes, file.relativePath)) {
                is SingleFileTransferResult.Completed -> completed++
                is SingleFileTransferResult.Rejected -> return TransferBatchResult(completed, file.displayName, result)
            }
        }
        return TransferBatchResult(completed, null, null)
    }
}

/** Sends a folder-sync batch (see FOLDER_SYNC_PLAN.md); declined where sync is not supported. */
interface SyncSender {
    suspend fun sendSync(
        endpoint: PeerEndpoint,
        files: List<OutgoingTransferFile>,
        sync: com.jagdeep.transfer.protocol.SyncTarget,
    ): TransferBatchResult
}

/** Sync sender for platforms without a transfer runtime. */
object UnavailableSyncSender : SyncSender {
    override suspend fun sendSync(endpoint: PeerEndpoint, files: List<OutgoingTransferFile>, sync: com.jagdeep.transfer.protocol.SyncTarget) =
        TransferBatchResult(0, null, SingleFileTransferResult.Rejected(null, "Folder sync isn't available on this device."))
}

data class OutgoingTransferFile(
    val sourceReference: String,
    val displayName: String,
    val sizeBytes: Long,
    val relativePath: String = displayName,
) {
    init {
        require(sourceReference.isNotBlank())
        require(displayName.isNotBlank())
        require(sizeBytes >= 0)
        require(com.jagdeep.transfer.protocol.ProtocolValidation.isSafeRelativePath(relativePath))
    }
}

data class TransferBatchResult(
    val completedCount: Int,
    val failedFileName: String?,
    val rejection: SingleFileTransferResult.Rejected?,
    val peer: com.jagdeep.transfer.protocol.PeerIdentity? = null,
) {
    val completed: Boolean get() = rejection == null
}

/**
 * Failed sends that can be re-sent this session. Source references are never written to history,
 * so retry is only possible while the app still holds the original selection in memory.
 */
interface TransferRetryService {
    /** History record id → recipient fingerprint when it was identified (to find its current address). */
    val retryableSends: kotlinx.coroutines.flow.StateFlow<Map<String, String?>>

    /** Re-sends the batch behind [historyId], to [endpoint] when given; null if it is no longer retryable. */
    suspend fun retrySend(historyId: String, endpoint: PeerEndpoint?): TransferBatchResult?
}

object UnavailableTransferRetryService : TransferRetryService {
    override val retryableSends: kotlinx.coroutines.flow.StateFlow<Map<String, String?>> = kotlinx.coroutines.flow.MutableStateFlow(emptyMap())
    override suspend fun retrySend(historyId: String, endpoint: PeerEndpoint?): TransferBatchResult? = null
}
