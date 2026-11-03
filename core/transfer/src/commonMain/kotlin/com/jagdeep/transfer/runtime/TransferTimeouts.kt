package com.jagdeep.transfer.runtime

/** Shared limits keep the socket alive for the complete human approval window. */
object TransferTimeouts {
    const val HANDSHAKE_MILLIS = 10_000
    const val APPROVAL_MILLIS = 120_000L
    const val SESSION_READ_MILLIS = 150_000
}
