package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Strict TLS 1.3 JVM transport. Callers provide an SSL context whose trust manager validates
 * the remote certificate; this class never installs a permissive trust manager.
 */
class JvmTlsTransport(private val sslContext: SSLContext) {
    suspend fun listen(
        bindAddress: InetAddress? = null,
        port: Int = 0,
        requireClientAuthentication: Boolean = true,
    ): JvmTlsListener = withContext(Dispatchers.IO) {
        val server = sslContext.serverSocketFactory.createServerSocket(port, 50, bindAddress) as SSLServerSocket
        server.enabledProtocols = arrayOf(Tls13)
        server.needClientAuth = requireClientAuthentication
        JvmTlsListener(server)
    }

    suspend fun connect(
        endpoint: PeerEndpoint,
        timeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): JvmTlsConnection = withContext(Dispatchers.IO) {
        require(timeoutMillis in 1..MAX_CONNECT_TIMEOUT_MILLIS) { "Connection timeout is outside the supported range." }
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.enabledProtocols = arrayOf(Tls13)
            socket.startHandshake()
            socket.soTimeout = TransferTimeouts.SESSION_READ_MILLIS
            JvmTlsConnection(socket)
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            throw failure
        }
    }

    suspend fun connectSafely(
        endpoint: PeerEndpoint,
        timeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    ): TlsConnectionAttempt = try {
        TlsConnectionAttempt.Connected(connect(endpoint, timeoutMillis))
    } catch (failure: Throwable) {
        TlsConnectionAttempt.Failed(DesktopConnectionErrorMapper.map(failure))
    }

    private companion object {
        const val Tls13 = "TLSv1.3"
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 10_000
        const val MAX_CONNECT_TIMEOUT_MILLIS = 60_000
    }
}

sealed interface TlsConnectionAttempt {
    data class Connected(val connection: JvmTlsConnection) : TlsConnectionAttempt
    data class Failed(val error: DesktopConnectionError) : TlsConnectionAttempt
}

sealed interface DesktopConnectionError {
    val userMessage: String

    data object TimedOut : DesktopConnectionError {
        override val userMessage = "Connection timed out. Check the IP address, port, and firewall on the receiving device."
    }
    data object Refused : DesktopConnectionError {
        override val userMessage = "The device refused the connection. Confirm Zynk is listening and allow it through the firewall."
    }
    data object NetworkUnavailable : DesktopConnectionError {
        override val userMessage = "The device cannot be reached on the current network."
    }
    data object PeerNotTrusted : DesktopConnectionError {
        override val userMessage = "Secure pairing could not be verified. Pair and verify this device before retrying."
    }
    data object SecureChannelFailed : DesktopConnectionError {
        override val userMessage = "A secure TLS connection could not be established with this device."
    }
    data object Unexpected : DesktopConnectionError {
        override val userMessage = "The connection could not be completed. Try again and check both devices."
    }
}

object DesktopConnectionErrorMapper {
    fun map(failure: Throwable): DesktopConnectionError = when (failure) {
        is SocketTimeoutException -> DesktopConnectionError.TimedOut
        is ConnectException -> DesktopConnectionError.Refused
        is NoRouteToHostException -> DesktopConnectionError.NetworkUnavailable
        is SSLHandshakeException, is SSLPeerUnverifiedException -> DesktopConnectionError.PeerNotTrusted
        is SSLException -> DesktopConnectionError.SecureChannelFailed
        else -> DesktopConnectionError.Unexpected
    }
}

class JvmTlsListener internal constructor(private val socket: SSLServerSocket) : AutoCloseable {
    val endpoint: ListeningEndpoint get() = ListeningEndpoint(socket.localPort)

    suspend fun accept(): JvmTlsConnection = withContext(Dispatchers.IO) {
        val accepted = socket.accept() as SSLSocket
        try {
            accepted.soTimeout = TransferTimeouts.HANDSHAKE_MILLIS
            accepted.enabledProtocols = arrayOf("TLSv1.3")
            accepted.startHandshake()
            accepted.soTimeout = TransferTimeouts.SESSION_READ_MILLIS
            JvmTlsConnection(accepted)
        } catch (failure: Throwable) {
            runCatching { accepted.close() }
            throw failure
        }
    }

    override fun close() = socket.close()
}

class JvmTlsConnection internal constructor(private val socket: SSLSocket) : AutoCloseable {
    private val input = socket.inputStream
    private val output = socket.outputStream

    /** SHA-256 pin of the peer certificate after the configured trust manager accepted it. */
    val peerCertificateFingerprint: String? = runCatching {
        certificateFingerprint(socket.session.peerCertificates.first() as X509Certificate)
    }.getOrNull()

    /** DER of the peer certificate as presented on this connection (used by nearby pairing). */
    val peerCertificateDer: ByteArray? = runCatching { (socket.session.peerCertificates.first() as X509Certificate).encoded }.getOrNull()

    /** Remote IP address, for per-address pairing rate limits. */
    val remoteAddress: String = socket.inetAddress?.hostAddress.orEmpty()

    fun sendControl(payload: ByteArray) = TlsControlFrameCodec.write(output, payload)
    fun receiveControl(): ByteArray = TlsControlFrameCodec.read(input)
    fun dataInput(): InputStream = input
    fun dataOutput(): OutputStream = output

    override fun close() {
        // TLS close may wait for the peer's close_notify; do not inherit the approval timeout.
        runCatching { socket.soTimeout = 1_000 }
        socket.close()
    }
}

object TlsControlFrameCodec {
    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Control message exceeds protocol limit." }
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    fun read(input: InputStream): ByteArray {
        val size = DataInputStream(input).readInt()
        if (size !in 0..TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) throw IllegalArgumentException("Invalid control frame size.")
        return ByteArray(size).also { bytes ->
            try {
                DataInputStream(input).readFully(bytes)
            } catch (failure: EOFException) {
                throw IllegalArgumentException("Incomplete control frame.", failure)
            }
        }
    }
}

private fun certificateFingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
    .digest(certificate.encoded)
    .joinToString(separator = "") { "%02x".format(it) }
