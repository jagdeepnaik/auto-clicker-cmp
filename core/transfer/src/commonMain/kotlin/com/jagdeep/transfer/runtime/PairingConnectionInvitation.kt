package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation

/**
 * Fresh QR/bootstrap material for the reciprocal pairing listener; never a reusable secret.
 *
 * It names the inviter's certificate only by SHA-256 fingerprint. The scanner pins that
 * fingerprint during the pairing TLS handshake and takes the certificate from the verified
 * connection, which keeps the QR small enough for phone cameras to read.
 */
data class PairingConnectionInvitation(
    val sessionId: String,
    val expiresAtEpochMillis: Long,
    val host: String,
    val port: Int,
    val initiatorDeviceId: String,
    val initiatorDisplayName: String,
    val initiatorFingerprint: String,
    val initiatorNonce: ByteArray,
) {
    init {
        require(sessionId.length in 1..64 && sessionId.all(::isTokenCharacter))
        require(expiresAtEpochMillis > 0)
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() })
        require(port in 1..65_535)
        require(initiatorDeviceId.length in 1..128 && initiatorDeviceId.all(::isTokenCharacter))
        require(initiatorDisplayName.isNotBlank() && initiatorDisplayName.length <= 80)
        require(ProtocolValidation.isSha256(initiatorFingerprint))
        require(initiatorNonce.size in 16..64)
    }
}

/** Version 3 replaces v2's embedded certificate with a pinned fingerprint. No silent downgrade. */
object PairingConnectionInvitationCodec {
    const val PREFIX = "zynk-pair:v3:"
    private const val PAIRING_PREFIX = "zynk-pair:"
    private const val FIELD_COUNT = 8

    fun encode(invitation: PairingConnectionInvitation): String = buildString {
        append(PREFIX)
        append(invitation.sessionId)
        append('.').append(invitation.expiresAtEpochMillis)
        append('.').append(PairingBase64Url.encode(invitation.host.encodeToByteArray()))
        append('.').append(invitation.port)
        append('.').append(invitation.initiatorDeviceId)
        append('.').append(PairingBase64Url.encode(invitation.initiatorDisplayName.encodeToByteArray()))
        append('.').append(PairingBase64Url.encode(invitation.initiatorFingerprint.hexToBytes()))
        append('.').append(PairingBase64Url.encode(invitation.initiatorNonce))
    }

    fun decode(payload: String): PairingConnectionInvitation {
        val code = payload.trim()
        require(code.startsWith(PREFIX)) {
            if (code.startsWith(PAIRING_PREFIX)) {
                "This code came from a different version of Zynk. Update Zynk on both devices, then try again."
            } else {
                "That isn't a Zynk pairing code."
            }
        }
        val parts = code.removePrefix(PREFIX).split('.')
        require(parts.size == FIELD_COUNT) { "The pairing code is incomplete. Scan it again." }
        val fingerprint = PairingBase64Url.decode(parts[6])
        require(fingerprint.size == 32) { "The pairing code is incomplete. Scan it again." }
        return PairingConnectionInvitation(
            sessionId = parts[0],
            expiresAtEpochMillis = parts[1].toLong(),
            host = PairingBase64Url.decode(parts[2]).decodeToString(),
            port = parts[3].toInt(),
            initiatorDeviceId = parts[4],
            initiatorDisplayName = PairingBase64Url.decode(parts[5]).decodeToString(),
            initiatorFingerprint = fingerprint.toLowercaseHex(),
            initiatorNonce = PairingBase64Url.decode(parts[7]),
        )
    }

    /** Session IDs are URL-safe random tokens so they need no escaping in the QR. */
    fun sessionIdFrom(randomBytes: ByteArray): String {
        require(randomBytes.size in 16..32)
        return PairingBase64Url.encode(randomBytes)
    }
}

private fun isTokenCharacter(character: Char) =
    character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character == '-' || character == '_'

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }

private fun ByteArray.toLowercaseHex(): String = joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
