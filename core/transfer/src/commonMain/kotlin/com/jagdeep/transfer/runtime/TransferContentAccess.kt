package com.jagdeep.transfer.runtime

/**
 * Platform boundary for opening content the user explicitly selected. References are opaque and
 * remain local: they must never be persisted in transfer history or sent over the network.
 */
interface TransferContentAccess {
    suspend fun openForRead(sourceReference: String): OpenTransferContent?
}

data class OpenTransferContent(
    val displayName: String,
    val sizeBytes: Long?,
    val source: TransferBinarySource,
) {
    init {
        require(displayName.isNotBlank())
        require(sizeBytes == null || sizeBytes >= 0)
    }
}

/** A sequential, bounded-memory binary source. Callers must always close it. */
interface TransferBinarySource : AutoCloseable {
    fun read(destination: ByteArray): Int
    override fun close()
}
