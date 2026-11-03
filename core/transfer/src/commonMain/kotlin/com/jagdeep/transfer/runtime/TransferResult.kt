package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.PeerIdentity

/** Outcome shared by platform transfer engines after protocol completion. */
sealed interface SingleFileTransferResult {
    data class Completed(val peer: PeerIdentity, val bytesTransferred: Long) : SingleFileTransferResult
    data class Rejected(val peer: PeerIdentity?, val message: String) : SingleFileTransferResult
}
