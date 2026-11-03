package com.jagdeep.transfer.runtime

import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * A locally-owned TLS identity. The fingerprint is the SHA-256 digest of its DER certificate.
 */
data class JvmTlsIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
) {
    val certificateFingerprint: String = certificate.certificateFingerprintSha256()
}

/**
 * Creates and persists a self-signed device identity in PKCS#12 format.
 *
 * The password is deliberately supplied by the caller: production desktop integrations must
 * retrieve it from the OS credential store rather than placing it in preferences or source code.
 */
class JvmPeerIdentityStore(
    private val keyStorePath: Path,
    private val alias: String = IDENTITY_ALIAS,
) {
    init {
        require(alias.isNotBlank()) { "Identity alias must not be blank" }
    }

    fun loadOrCreate(password: CharArray): JvmTlsIdentity =
        if (Files.exists(keyStorePath)) load(password) else create(password)

    fun load(password: CharArray): JvmTlsIdentity {
        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE)
        Files.newInputStream(keyStorePath).use { input -> keyStore.load(input, password) }
        val privateKey = keyStore.getKey(alias, password) as? PrivateKey
            ?: throw IllegalStateException("No private identity key found at $keyStorePath")
        val certificate = keyStore.getCertificate(alias) as? X509Certificate
            ?: throw IllegalStateException("No X.509 identity certificate found at $keyStorePath")
        certificate.checkValidity()
        return JvmTlsIdentity(privateKey, certificate)
    }

    fun create(password: CharArray): JvmTlsIdentity {
        require(password.isNotEmpty()) { "PKCS#12 password must not be empty" }
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"), SecureRandom())
        }.generateKeyPair()
        val certificate = createSelfSignedCertificate(keyPair.private, keyPair.public.encoded)
        val keyStore = KeyStore.getInstance(KEY_STORE_TYPE).apply { load(null, null) }
        keyStore.setKeyEntry(alias, keyPair.private, password, arrayOf(certificate))
        storeAtomically(keyStore, password)
        return JvmTlsIdentity(keyPair.private, certificate)
    }

    private fun createSelfSignedCertificate(
        privateKey: PrivateKey,
        publicKeyEncoded: ByteArray,
    ): X509Certificate {
        val now = System.currentTimeMillis()
        val publicKey = java.security.KeyFactory.getInstance("EC")
            .generatePublic(java.security.spec.X509EncodedKeySpec(publicKeyEncoded))
        val subject = X500Name("CN=Zynk Peer")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(160, SecureRandom()).max(BigInteger.ONE),
            Date(now - CLOCK_SKEW_MILLIS),
            Date(now + CERTIFICATE_VALIDITY_MILLIS),
            subject,
            publicKey,
        )
            .addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            .addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        val holder = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(privateKey))
        return JcaX509CertificateConverter().getCertificate(holder).also { certificate ->
            certificate.verify(publicKey)
        }
    }

    private fun storeAtomically(keyStore: KeyStore, password: CharArray) {
        val storagePath = keyStorePath.toAbsolutePath()
        val storageDirectory = requireNotNull(storagePath.parent) { "Identity path must have a parent directory" }
        Files.createDirectories(storageDirectory)
        val temporaryPath = Files.createTempFile(storageDirectory, ".${storagePath.fileName}.", ".part")
        try {
            Files.newOutputStream(temporaryPath).use { output: OutputStream -> keyStore.store(output, password) }
            try {
                Files.move(temporaryPath, storagePath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporaryPath, storagePath)
            }
        } finally {
            Files.deleteIfExists(temporaryPath)
        }
    }

    companion object {
        const val IDENTITY_ALIAS = "zynk-identity"
        private const val KEY_STORE_TYPE = "PKCS12"
        private const val CLOCK_SKEW_MILLIS = 60_000L
        private const val CERTIFICATE_VALIDITY_MILLIS = 5L * 365 * 24 * 60 * 60 * 1_000
    }
}

/** Builds a mutual-TLS context which trusts only the certificates explicitly paired by the user. */
object JvmTrustedTlsContext {
    fun create(identity: JvmTlsIdentity, password: CharArray, trustedPeerCertificates: Collection<X509Certificate>): SSLContext {
        val identityStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(JvmPeerIdentityStore.IDENTITY_ALIAS, identity.privateKey, password, arrayOf(identity.certificate))
        }
        val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(identityStore, password)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trustedPeerCertificates.forEachIndexed { index, certificate ->
            trustStore.setCertificateEntry("peer-$index-${certificate.certificateFingerprintSha256()}", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagerFactory.keyManagers, trustManagerFactory.trustManagers, SecureRandom())
        }
    }

}

/**
 * TLS contexts reserved for the pairing listener. The server authenticates itself with the
 * QR-pinned certificate; it deliberately does not request a client certificate because the
 * scanner proves its fresh identity with a signed pairing transcript at the next protocol layer.
 */
object JvmPairingTlsContext {
    fun server(identity: JvmTlsIdentity): SSLContext = context(identity, emptyList())

    /** Kept for tests that already hold the inviter certificate; production scanners pin by fingerprint. */
    fun client(identity: JvmTlsIdentity, expectedServerCertificate: X509Certificate): SSLContext =
        pinnedClient(identity, expectedServerCertificate.certificateFingerprintSha256()).sslContext

    /**
     * Scanner context for a v3 invitation: the server certificate is accepted only when its
     * SHA-256 equals the fingerprint from the QR. The verified certificate is then available
     * from [PinnedPairingClient.trustManager].
     */
    fun pinnedClient(identity: JvmTlsIdentity, expectedFingerprint: String): PinnedPairingClient {
        val trustManager = PinnedPairingTrustManager(expectedFingerprint) { it.certificateFingerprintSha256() }
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers(identity), arrayOf(trustManager), SecureRandom()) }
        return PinnedPairingClient(sslContext, trustManager)
    }

    /** Mutual TLS for nearby pairing: both sides present their device certificate; none is pinned. */
    fun nearby(identity: JvmTlsIdentity): SSLContext =
        SSLContext.getInstance("TLS").apply { init(keyManagers(identity), arrayOf(NearbyPairingTrustManager()), SecureRandom()) }

    private fun context(identity: JvmTlsIdentity, trustedCertificates: Collection<X509Certificate>): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        trustedCertificates.forEachIndexed { index, certificate -> trustStore.setCertificateEntry("pairing-$index", certificate) }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(keyManagers(identity), trustManagerFactory.trustManagers, SecureRandom())
        }
    }

    private fun keyManagers(identity: JvmTlsIdentity): Array<javax.net.ssl.KeyManager> {
        val password = "zynk-pairing-ephemeral".toCharArray()
        val identityStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(JvmPeerIdentityStore.IDENTITY_ALIAS, identity.privateKey, password, arrayOf(identity.certificate))
        }
        return KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(identityStore, password) }.keyManagers
    }
}

class PinnedPairingClient(val sslContext: SSLContext, val trustManager: PinnedPairingTrustManager)

fun X509Certificate.certificateFingerprintSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(encoded)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }
