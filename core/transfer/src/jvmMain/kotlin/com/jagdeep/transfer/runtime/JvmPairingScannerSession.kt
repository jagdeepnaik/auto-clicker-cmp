package com.jagdeep.transfer.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scanner-side pairing transport. Caller must collect local code confirmation before calling it. */
class JvmPairingScannerSession(
    private val identity: JvmTlsIdentity,
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
        val pinned = JvmPairingTlsContext.pinnedClient(identity, invitation.initiatorFingerprint)
        val connection = try {
            JvmTlsTransport(pinned.sslContext).connect(PeerEndpoint(invitation.host, invitation.port))
        } catch (_: javax.net.ssl.SSLException) {
            return PairingScannerResult.Rejected("The other device's identity didn't match the scanned code. Make a new code and try again.")
        } catch (_: Exception) {
            return PairingScannerResult.Rejected("Couldn't reach the other device. Check that both are on the same network.")
        }
        // The QR only names the certificate; the record is built from the one TLS just verified.
        val inviter = pinned.trustManager.verifiedCertificate?.let { certificate ->
            JvmPairingVerifier().verify(
                PairingInvitation(invitation.initiatorDeviceId, invitation.initiatorDisplayName, invitation.initiatorFingerprint, certificate.encoded),
            )
        }
        if (inviter !is PairingVerificationResult.Verified) {
            runCatching { connection.close() }
            return PairingScannerResult.Rejected("The other device's identity couldn't be verified.")
        }
        return try {
            val transcriptHash = invitation.initiatorNonce.toHexSha256(invitation.sessionId, identity.certificateFingerprint)
            connection.sendPairingMessage(
                JvmPairingSessionDispatcher(JvmPairingIdentityProof(identity)).initialProof(
                    signer = JvmPairingIdentityProof(identity),
                    sessionId = invitation.sessionId,
                    transcriptHash = transcriptHash,
                    displayName = displayName,
                ),
            )
            when (val response = connection.receivePairingMessage()) {
                is PairingWireMessage.Confirmation -> {
                    if (response.sessionId != invitation.sessionId || response.transcriptHash != transcriptHash || !response.accepted) {
                        PairingScannerResult.Rejected("Pairing was not accepted by the other device.")
                    } else {
                        connection.sendPairingMessage(PairingWireMessage.Acknowledgement(PairingProtocol.CURRENT_VERSION, invitation.sessionId, transcriptHash))
                        val finalAcknowledgement = connection.receivePairingMessage() as? PairingWireMessage.Acknowledgement
                        if (finalAcknowledgement?.sessionId != invitation.sessionId || finalAcknowledgement.transcriptHash != transcriptHash) {
                            PairingScannerResult.Rejected("The other device did not complete pairing acknowledgement.")
                        } else {
                            try {
                                trustedPeers.upsertVerified(inviter.peer)
                                PairingScannerResult.Acknowledged(inviter.peer, transcriptHash)
                            } catch (_: Exception) {
                                PairingScannerResult.Rejected("Could not save the paired device. Retry pairing.")
                            }
                        }
                    }
                }
                is PairingWireMessage.Rejection -> PairingScannerResult.Rejected(response.reason)
                else -> PairingScannerResult.Rejected("The other device sent an invalid pairing response.")
            }
        } catch (_: Exception) {
            PairingScannerResult.Rejected("The pairing connection ended before confirmation.")
        } finally {
            runCatching { connection.close() }
        }
    }
}

internal fun ByteArray.toHexSha256(sessionId: String, scannerFingerprint: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(this + sessionId.encodeToByteArray() + scannerFingerprint.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
