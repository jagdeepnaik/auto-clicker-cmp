package com.jagdeep.transfer.runtime

/** Local transport capability only; Ready does not imply that a remote peer is trusted or reachable. */
sealed interface TransferReadiness {
    data object Starting : TransferReadiness
    data object Ready : TransferReadiness
    data object Stopped : TransferReadiness
    /** Message must be safe, actionable product copy, never a raw exception. */
    data class Unavailable(val message: String) : TransferReadiness
}
