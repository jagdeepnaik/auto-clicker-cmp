package com.jagdeep.transfer.runtime

import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Nearby pairing only: accepts the peer's single self-signed device certificate without pinning,
 * because no prior trust exists. Authenticity is established afterwards by both users comparing a
 * code bound to the observed certificates (see NearbyPairing.kt and the threat model). Never used
 * for transfers, which require a certificate saved by verified pairing.
 * (Identical copies live in jvmMain and androidMain; the two do not share a source set.)
 */
class NearbyPairingTrustManager : X509ExtendedTrustManager() {
    private fun check(chain: Array<out X509Certificate>?) {
        val leaf = chain?.singleOrNull() ?: throw CertificateException("A nearby device must present exactly one certificate.")
        leaf.checkValidity()
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = check(chain)
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = check(chain)
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
