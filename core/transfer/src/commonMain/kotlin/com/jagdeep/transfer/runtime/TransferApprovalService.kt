package com.jagdeep.transfer.runtime

import kotlinx.coroutines.flow.StateFlow

/** Feature-facing approval boundary; receiving is impossible until a user decides. */
interface TransferApprovalService {
    val pendingIncoming: StateFlow<List<IncomingTransferRequest>>
    suspend fun decideIncoming(requestId: String, accepted: Boolean): Boolean
}
