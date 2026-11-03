package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.ProtocolValidation
import kotlinx.serialization.Serializable

/** Versioned, self-contained payload for QR or manual verified pairing. */
@Serializable
data class PairingInvitation(
    val deviceId: String,
    val displayName: String,
    val certificateFingerprint: String,
    val certificateDer: ByteArray,
) {
    init {
        require(deviceId.isNotBlank() && deviceId.length <= 128)
        require(displayName.isNotBlank() && displayName.length <= 80)
        require(ProtocolValidation.isSha256(certificateFingerprint))
        require(certificateDer.size in 1..16_384)
    }
}

/** Compact URL-safe payload; certificate fingerprint verification is performed by platform crypto before trust is saved. */
object PairingInvitationCodec {
    private const val PREFIX = "zynk-pair:v1:"

    fun encode(invitation: PairingInvitation): String = buildString {
        append(PREFIX)
        append(PairingBase64Url.encode(invitation.deviceId.encodeToByteArray()))
        append('.')
        append(PairingBase64Url.encode(invitation.displayName.encodeToByteArray()))
        append('.')
        append(invitation.certificateFingerprint)
        append('.')
        append(PairingBase64Url.encode(invitation.certificateDer))
    }

    fun decode(payload: String): PairingInvitation {
        require(payload.startsWith(PREFIX)) { "Unsupported pairing invitation." }
        val parts = payload.removePrefix(PREFIX).split('.')
        require(parts.size == 4) { "Invalid pairing invitation." }
        return PairingInvitation(
            deviceId = PairingBase64Url.decode(parts[0]).decodeToString(),
            displayName = PairingBase64Url.decode(parts[1]).decodeToString(),
            certificateFingerprint = parts[2],
            certificateDer = PairingBase64Url.decode(parts[3]),
        )
    }
}

internal object PairingBase64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(input: ByteArray): String {
        val output = StringBuilder((input.size * 4 + 2) / 3)
        var index = 0
        while (index < input.size) {
            val first = input[index++].toInt() and 0xff
            val second = input.getOrNull(index++)?.toInt()?.and(0xff)
            val third = input.getOrNull(index++)?.toInt()?.and(0xff)
            output.append(ALPHABET[first ushr 2])
            output.append(ALPHABET[((first and 0x03) shl 4) or ((second ?: 0) ushr 4)])
            if (second != null) output.append(ALPHABET[((second and 0x0f) shl 2) or ((third ?: 0) ushr 6)])
            if (third != null) output.append(ALPHABET[third and 0x3f])
        }
        return output.toString()
    }

    fun decode(value: String): ByteArray {
        require(value.isNotEmpty() && value.length % 4 != 1) { "Invalid pairing encoding." }
        val output = ArrayList<Byte>((value.length * 3) / 4)
        var index = 0
        while (index < value.length) {
            val first = decodeCharacter(value[index++])
            val second = decodeCharacter(value[index++])
            val third = value.getOrNull(index++)?.let(::decodeCharacter)
            val fourth = value.getOrNull(index++)?.let(::decodeCharacter)
            output += ((first shl 2) or (second ushr 4)).toByte()
            if (third != null) output += (((second and 0x0f) shl 4) or (third ushr 2)).toByte()
            if (fourth != null) output += ((((third ?: 0) and 0x03) shl 6) or fourth).toByte()
        }
        return output.toByteArray()
    }

    private fun decodeCharacter(character: Char): Int = ALPHABET.indexOf(character).takeIf { it >= 0 }
        ?: throw IllegalArgumentException("Invalid pairing encoding.")
}
