package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object PairingProtocol {
    /** 3 adds nearby (numeric comparison) pairing messages; both platforms ship together. */
    const val CURRENT_VERSION = 3
}

/** Pairing-only messages. These are never decoded by a file transfer listener. */
@Serializable
sealed interface PairingWireMessage {
    val version: Int
    val sessionId: String

    @Serializable
    @SerialName("identity_proof")
    data class IdentityProof(
        override val version: Int,
        val proof: PairingIdentityProof,
    ) : PairingWireMessage {
        override val sessionId: String get() = proof.sessionId
    }

    @Serializable
    @SerialName("confirmation")
    data class Confirmation(
        override val version: Int,
        override val sessionId: String,
        val transcriptHash: String,
        val accepted: Boolean,
    ) : PairingWireMessage {
        init { validateSessionAndTranscript(sessionId, transcriptHash) }
    }

    @Serializable
    @SerialName("acknowledgement")
    data class Acknowledgement(
        override val version: Int,
        override val sessionId: String,
        val transcriptHash: String,
    ) : PairingWireMessage {
        init { validateSessionAndTranscript(sessionId, transcriptHash) }
    }

    /** Nearby pairing: initiator introduces itself and commits to its nonce before seeing the responder's. */
    @Serializable
    @SerialName("sas_hello")
    data class SasHello(
        override val version: Int,
        override val sessionId: String,
        val deviceId: String,
        val displayName: String,
        val commitment: String,
    ) : PairingWireMessage {
        init {
            validateIdentityClaim(deviceId, displayName)
            require(commitment.isLowerHex(64))
        }
    }

    /** Nearby pairing: responder's identity claim and nonce, sent before it learns the initiator's nonce. */
    @Serializable
    @SerialName("sas_nonce")
    data class SasNonce(
        override val version: Int,
        override val sessionId: String,
        val deviceId: String,
        val displayName: String,
        val nonce: String,
    ) : PairingWireMessage {
        init {
            validateIdentityClaim(deviceId, displayName)
            require(nonce.isLowerHex(64))
        }
    }

    /** Nearby pairing: initiator opens its commitment. */
    @Serializable
    @SerialName("sas_reveal")
    data class SasReveal(
        override val version: Int,
        override val sessionId: String,
        val nonce: String,
    ) : PairingWireMessage {
        init { require(nonce.isLowerHex(64)) }
    }

    @Serializable
    @SerialName("rejection")
    data class Rejection(
        override val version: Int,
        override val sessionId: String,
        val reason: String,
    ) : PairingWireMessage {
        init {
            require(sessionId.isNotBlank() && sessionId.length <= 128)
            require(reason.isNotBlank() && reason.length <= 160)
        }
    }
}

object PairingWireMessageCodec {
    private val json = Json { classDiscriminator = "type"; encodeDefaults = true; ignoreUnknownKeys = false }

    fun encode(message: PairingWireMessage): ByteArray {
        validate(message)
        val bytes = json.encodeToString(PairingWireMessage.serializer(), message).encodeToByteArray()
        require(bytes.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Pairing message exceeds protocol limit." }
        return bytes
    }

    fun decode(bytes: ByteArray): PairingWireMessage {
        require(bytes.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Pairing message exceeds protocol limit." }
        return json.decodeFromString(PairingWireMessage.serializer(), bytes.decodeToString()).also(::validate)
    }

    private fun validate(message: PairingWireMessage) {
        require(message.version == PairingProtocol.CURRENT_VERSION) { "Unsupported pairing protocol version." }
        require(message.sessionId.isNotBlank() && message.sessionId.length <= 128) { "Invalid pairing session." }
        if (message is PairingWireMessage.IdentityProof) {
            require(message.proof.transcriptHash.length == 64) { "Invalid pairing transcript." }
        }
    }
}

private fun validateSessionAndTranscript(sessionId: String, transcriptHash: String) {
    require(sessionId.isNotBlank() && sessionId.length <= 128)
    require(transcriptHash.length == 64 && transcriptHash.all { it in '0'..'9' || it in 'a'..'f' })
}

private fun validateIdentityClaim(deviceId: String, displayName: String) {
    require(deviceId.isNotBlank() && deviceId.length <= 128)
    require(displayName.isNotBlank() && displayName.length <= 80)
}

internal fun String.isLowerHex(length: Int) = this.length == length && all { it in '0'..'9' || it in 'a'..'f' }
