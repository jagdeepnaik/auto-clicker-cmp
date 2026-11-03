package com.jagdeep.transfer.runtime

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Pairing-scanner trust: accepts exactly one server certificate, identified by the SHA-256 of
 * its DER encoding as carried in the v3 pairing QR. There is no CA path and no fallback; any
 * other certificate aborts the handshake. TLS 1.3 then proves possession of its private key.
 * (Identical copies live in jvmMain and androidMain; the two do not share a source set.)
 */
class PinnedPairingTrustManager(
    private val expectedFingerprint: String,
    private val fingerprintOf: (X509Certificate) -> String,
) : X509ExtendedTrustManager() {
    @Volatile
    var verifiedCertificate: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("The pairing device sent no certificate.")
        leaf.checkValidity()
        if (fingerprintOf(leaf) != expectedFingerprint) {
            throw CertificateException("The pairing device's certificate doesn't match the scanned code.")
        }
        verifiedCertificate = leaf
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("Pairing scanners never accept client certificates.")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
