package com.jagdeep.transfer.runtime

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.security.auth.x500.X500Principal

/** Device TLS identity backed by Android Keystore; its private key never enters app storage. */
data class AndroidTlsIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val certificateFingerprint: String = certificate.androidCertificateFingerprint()
}

class AndroidPeerIdentityStore(context: Context) {
    private val appContext = context.applicationContext

    fun loadOrCreate(): AndroidTlsIdentity {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        // Identities created before 2026-10-29 lacked DIGEST_NONE, so TLS handshakes failed with
        // INCOMPATIBLE_DIGEST. Authorizations are fixed at creation: replace the key. Peers that
        // paired with the old certificate must pair again; nothing else is stored under it.
        if (keyStore.containsAlias(IDENTITY_ALIAS) && !keyStore.canSignTlsHandshakes()) keyStore.deleteEntry(IDENTITY_ALIAS)
        if (!keyStore.containsAlias(IDENTITY_ALIAS)) createIdentity()
        val refreshed = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        val privateKey = refreshed.getKey(IDENTITY_ALIAS, null) as? PrivateKey
            ?: error("Android Keystore did not provide the Zynk private key.")
        val certificate = refreshed.getCertificate(IDENTITY_ALIAS) as? X509Certificate
            ?: error("Android Keystore did not provide the Zynk certificate.")
        certificate.checkValidity()
        return AndroidTlsIdentity(privateKey, certificate)
    }

    private fun createIdentity() {
        val now = System.currentTimeMillis()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEY_STORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    IDENTITY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    // Conscrypt hashes the TLS transcript itself and asks Keystore to sign the raw
                    // digest (NONEwithECDSA), so TLS client/server authentication needs DIGEST_NONE.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setCertificateSubject(X500Principal("CN=Zynk Peer"))
                    .setCertificateSerialNumber(BigInteger(160, SecureRandom()).max(BigInteger.ONE))
                    .setCertificateNotBefore(Date(now - CLOCK_SKEW_MILLIS))
                    .setCertificateNotAfter(Date(now + CERTIFICATE_VALIDITY_MILLIS))
                    .build(),
            )
            generateKeyPair()
        }
    }

    /** Replace a key only when it is positively known to lack DIGEST_NONE; never on a lookup failure. */
    private fun KeyStore.canSignTlsHandshakes(): Boolean = runCatching {
        val key = getKey(IDENTITY_ALIAS, null) as PrivateKey
        val info = KeyFactory.getInstance(key.algorithm, ANDROID_KEY_STORE).getKeySpec(key, KeyInfo::class.java)
        KeyProperties.DIGEST_NONE in info.digests
    }.getOrDefault(true)

    companion object {
        const val IDENTITY_ALIAS = "zynk-tls-identity"
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val CLOCK_SKEW_MILLIS = 60_000L
        private const val CERTIFICATE_VALIDITY_MILLIS = 5L * 365 * 24 * 60 * 60 * 1_000
    }
}

/** Mutual-TLS context that trusts only certificates supplied by a verified pairing. */
object AndroidTrustedTlsContext {
    fun create(identity: AndroidTlsIdentity, trustedPeerCertificates: Collection<X509Certificate>): SSLContext {
        val identityStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identityStore, null)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trustedPeerCertificates.forEachIndexed { index, certificate ->
            trustStore.setCertificateEntry("peer-$index-${certificate.androidCertificateFingerprint()}", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, SecureRandom())
        }
    }
}

/** Pairing-only TLS: scanner pins the QR inviter certificate; server does not request client TLS auth. */
object AndroidPairingTlsContext {
    fun server(identity: AndroidTlsIdentity): SSLContext {
        val identityStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identityStore, null) }
        return SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, null, SecureRandom()) }
    }

    /** Mutual TLS for nearby pairing: both sides present their device certificate; none is pinned. */
    fun nearby(identity: AndroidTlsIdentity): SSLContext {
        val identityStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identityStore, null) }
        return SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, arrayOf(NearbyPairingTrustManager()), SecureRandom()) }
    }

    /** Scanner context for a v3 invitation: accepts only the server certificate whose SHA-256 is [expectedFingerprint]. */
    fun pinnedClient(identity: AndroidTlsIdentity, expectedFingerprint: String): AndroidPinnedPairingClient {
        val identityStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identityStore, null) }
        val trustManager = PinnedPairingTrustManager(expectedFingerprint) { it.androidCertificateFingerprint() }
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagerFactory.keyManagers, arrayOf(trustManager), SecureRandom()) }
        return AndroidPinnedPairingClient(sslContext, trustManager)
    }
}

class AndroidPinnedPairingClient(val sslContext: SSLContext, val trustManager: PinnedPairingTrustManager)

private fun X509Certificate.androidCertificateFingerprint(): String = MessageDigest.getInstance("SHA-256")
    .digest(encoded)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
