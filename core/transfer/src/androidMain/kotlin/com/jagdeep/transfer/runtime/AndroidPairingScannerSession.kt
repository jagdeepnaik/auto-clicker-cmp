package com.jagdeep.transfer.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import java.security.MessageDigest

class AndroidPairingScannerSession(
    private val identity: AndroidTlsIdentity,
    private val trustedPeers: TrustedPeerRepository,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    /** Main-safe: the whole socket exchange, including close, runs on the IO dispatcher. */
    suspend fun connectAndConfirm(
        invitation: PairingConnectionInvitation,
        displayName: String,
        localCodeConfirmed: Boolean,
    ): PairingScannerResult = withContext(Dispatchers.IO) { exchange(invitation, displayName, localCodeConfirmed) }

    private suspend fun exchange(invitation: PairingConnectionInvitation, displayName: String, localCodeConfirmed: Boolean): PairingScannerResult {
        if (!localCodeConfirmed) return PairingScannerResult.Rejected("Pairing was declined on this device.")
        if (nowEpochMillis() >= invitation.expiresAtEpochMillis) return PairingScannerResult.Rejected("Pairing invitation expired. Ask the other device to create a new one.")
        val pinned = AndroidPairingTlsContext.pinnedClient(identity, invitation.initiatorFingerprint)
        val connection = try {
            AndroidTlsTransport(pinned.sslContext).connect(PeerEndpoint(invitation.host, invitation.port))
        } catch (_: javax.net.ssl.SSLException) {
            return PairingScannerResult.Rejected("The other device's identity didn't match the scanned code. Make a new code and try again.")
        } catch (_: Exception) {
            return PairingScannerResult.Rejected("Couldn't reach the other device. Check that both are on the same network.")
        }
        // The QR only names the certificate; the record is built from the one TLS just verified.
        val verified = pinned.trustManager.verifiedCertificate?.let { certificate ->
            AndroidPairingVerifier().verify(
                PairingInvitation(invitation.initiatorDeviceId, invitation.initiatorDisplayName, invitation.initiatorFingerprint, certificate.encoded),
            )
        }
        if (verified !is PairingVerificationResult.Verified) {
            runCatching { connection.close() }
            return PairingScannerResult.Rejected("The other device's identity couldn't be verified.")
        }
        val inviter = verified.peer
        return try {
            val transcript = MessageDigest.getInstance("SHA-256").digest(invitation.initiatorNonce + invitation.sessionId.encodeToByteArray() + identity.certificateFingerprint.encodeToByteArray()).joinToString("") { "%02x".format(it) }
            connection.sendPairingMessage(AndroidPairingSessionDispatcher(AndroidPairingIdentityProof(identity)).initialProof(AndroidPairingIdentityProof(identity), invitation.sessionId, transcript, displayName))
            when (val response = connection.receivePairingMessage()) {
                is PairingWireMessage.Confirmation -> if (response.sessionId == invitation.sessionId && response.transcriptHash == transcript && response.accepted) {
                    connection.sendPairingMessage(PairingWireMessage.Acknowledgement(PairingProtocol.CURRENT_VERSION, invitation.sessionId, transcript))
                    val finalAck = connection.receivePairingMessage() as? PairingWireMessage.Acknowledgement
                    if (finalAck?.sessionId == invitation.sessionId && finalAck.transcriptHash == transcript) {
                        trustedPeers.upsertVerified(inviter); PairingScannerResult.Acknowledged(inviter, transcript)
                    } else PairingScannerResult.Rejected("The other device did not complete pairing acknowledgement.")
                } else PairingScannerResult.Rejected("Pairing was not accepted by the other device.")
                is PairingWireMessage.Rejection -> PairingScannerResult.Rejected(response.reason)
                else -> PairingScannerResult.Rejected("The other device sent an invalid pairing response.")
            }
        } catch (_: Exception) { PairingScannerResult.Rejected("The pairing connection ended before confirmation.") }
        finally { runCatching { connection.close() } }
    }
}
