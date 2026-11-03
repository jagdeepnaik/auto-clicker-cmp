package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

enum class TransferDirection { SENDING, RECEIVING }

/** Non-sensitive, in-memory activity shown while an approved transfer is running. */
data class ActiveTransfer(
    val id: String,
    val direction: TransferDirection,
    val peerName: String,
    val fileName: String,
    val progress: TransferProgress,
) {
    init {
        require(id.isNotBlank())
        require(peerName.isNotBlank())
        require(fileName.isNotBlank())
    }
}

/** Progress of an incoming transfer the user approved; platform runtimes report it to the app shell. */
data class IncomingTransferActivity(
    val id: String,
    val peer: com.jagdeep.transfer.protocol.PeerIdentity,
    val manifest: com.jagdeep.transfer.protocol.TransferManifest,
    val progress: TransferProgress,
) {
    /** One name for a single file, otherwise a count. */
    val label: String get() = manifest.entries.singleOrNull()?.relativePath ?: "${manifest.entries.size} files"
}

interface TransferProgressService {
    val activeTransfers: StateFlow<List<ActiveTransfer>>
}

/** Exposes the current local listener port without exposing transport implementation details. */
interface TransferListeningPortProvider {
    val transferPort: StateFlow<Int?>
    val readiness: StateFlow<TransferReadiness>
}

object EmptyTransferListeningPortProvider : TransferListeningPortProvider {
    override val transferPort: StateFlow<Int?> = MutableStateFlow(null)
    override val readiness: StateFlow<TransferReadiness> = MutableStateFlow(TransferReadiness.Stopped)
}

/** Cancels an active local stream. The transport closes its connection so a partial destination is never finalized. */
interface TransferCancellationService {
    suspend fun cancelTransfer(id: String): Boolean
}

object EmptyTransferProgressService : TransferProgressService {
    override val activeTransfers: StateFlow<List<ActiveTransfer>> = MutableStateFlow(emptyList())
}
