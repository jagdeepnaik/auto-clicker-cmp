package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ControlMessage
import com.jagdeep.transfer.protocol.ControlMessageCodec
import com.jagdeep.transfer.protocol.PeerIdentity
import com.jagdeep.transfer.protocol.ProtocolValidation

/** Control-plane helpers for a mutually authenticated Android TLS connection. */
internal fun AndroidTlsConnection.sendMessage(message: ControlMessage) =
    sendControl(ControlMessageCodec.encode(message))

internal fun AndroidTlsConnection.receiveMessage(): ControlMessage {
    val message = ControlMessageCodec.decode(receiveControl())
    require(ProtocolValidation.validate(message) == null) { "Peer sent an invalid control message." }
    return message
}

/**
 * Rejects a peer whose TLS certificate is not the one it claims or was not saved by pairing.
 * Call this before exposing an incoming offer to receiver approval UI.
 */
internal suspend fun AndroidTlsConnection.receiveHelloAndAuthorize(
    authorizer: TrustedPeerAuthorizer,
): PeerIdentity {
    val hello = receiveMessage() as? ControlMessage.Hello
        ?: throw IllegalArgumentException("Peer did not identify itself.")
    return when (authorizer.authorize(requireNotNull(peerCertificateFingerprint) { "Peer did not provide a TLS certificate." }, hello.peer)) {
        is TrustedPeerAuthorization.Authorized -> hello.peer
        is TrustedPeerAuthorization.Rejected -> throw SecurityException("Untrusted peer.")
    }
}
