package com.jagdeep.transfer.runtime

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class JvmPeerIdentityStoreTest {
    @Test
    fun `identity is persisted and loaded with the same certificate fingerprint`() {
        val directory = Files.createTempDirectory("zynk-identity-test")
        val password = "test-only-secret".toCharArray()
        try {
            val store = JvmPeerIdentityStore(directory.resolve("identity.p12"))
            val created = store.loadOrCreate(password)
            val loaded = store.load(password)

            assertEquals(created.certificateFingerprint, loaded.certificateFingerprint)
            assertEquals(64, loaded.certificateFingerprint.length)
            assertTrue(Files.size(directory.resolve("identity.p12")) > 0)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            password.fill('\u0000')
        }
    }

    @Test
    fun `each new identity has a distinct fingerprint`() {
        val directory = Files.createTempDirectory("zynk-identity-test")
        val password = "test-only-secret".toCharArray()
        try {
            val first = JvmPeerIdentityStore(directory.resolve("first.p12")).create(password)
            val second = JvmPeerIdentityStore(directory.resolve("second.p12")).create(password)

            assertNotEquals(first.certificateFingerprint, second.certificateFingerprint)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            password.fill('\u0000')
        }
    }

    @Test
    fun `trusted context accepts only explicitly supplied peer certificates`() {
        val directory = Files.createTempDirectory("zynk-identity-test")
        val password = "test-only-secret".toCharArray()
        try {
            val local = JvmPeerIdentityStore(directory.resolve("local.p12")).create(password)
            val peer = JvmPeerIdentityStore(directory.resolve("peer.p12")).create(password)

            val context = JvmTrustedTlsContext.create(local, password, listOf(peer.certificate))

            assertTrue(context.supportedSSLParameters.protocols.contains("TLSv1.3"))
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
            password.fill('\u0000')
        }
    }
}
