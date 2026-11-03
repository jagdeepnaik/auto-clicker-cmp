package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.ControlMessageCodec
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.ProtocolValidation
import java.io.InputStream
import java.io.OutputStream

internal fun JvmTlsConnection.sendMessage(message: ControlMessage) = sendControl(ControlMessageCodec.encode(message))

internal fun JvmTlsConnection.receiveMessage(): ControlMessage {
    val message = ControlMessageCodec.decode(receiveControl())
    require(ProtocolValidation.validate(message) == null) { "Peer sent an invalid control message." }
    return message
}

internal suspend fun JvmTlsConnection.receiveHelloAndVerify(authorizer: TrustedPeerAuthorizer? = null): PeerIdentity {
    val hello = receiveMessage() as? ControlMessage.Hello
        ?: throw IllegalArgumentException("Peer did not identify itself.")
    val certificateFingerprint = requireNotNull(peerCertificateFingerprint) { "Peer did not provide a TLS certificate." }
    require(hello.peer.publicKeyFingerprint == certificateFingerprint) { "Peer identity does not match its TLS certificate." }
    authorizer?.authorize(certificateFingerprint, hello.peer)?.also { authorization ->
        require(authorization is TrustedPeerAuthorization.Authorized) {
            (authorization as TrustedPeerAuthorization.Rejected).message
        }
    }
    return hello.peer
}

/** Limits a raw connection stream to one declared manifest entry without closing the socket. */
internal class LimitedInputStream(
    private val delegate: InputStream,
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = delegate.read()
        if (value >= 0) remaining--
        return value
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        if (remaining == 0L) return -1
        val count = delegate.read(bytes, offset, minOf(length.toLong(), remaining).toInt())
        if (count > 0) remaining -= count
        return count
    }

    fun drain() {
        val buffer = ByteArray(32 * 1024)
        while (true) if (read(buffer) < 0) return
    }

    override fun close() = Unit
}

internal fun InputStream.copyExactlyTo(
    output: OutputStream,
    expectedBytes: Long,
    onBytesCopied: (Long) -> Unit,
): Long {
    val buffer = ByteArray(512 * 1024)
    var copied = 0L
    while (copied < expectedBytes) {
        val count = read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - copied).toInt())
        if (count < 0) throw IllegalArgumentException("Source ended before its declared size.")
        if (count == 0) continue
        output.write(buffer, 0, count)
        copied += count
        onBytesCopied(copied)
    }
    require(read() < 0) { "Source exceeds its declared size." }
    output.flush()
    return copied
}
