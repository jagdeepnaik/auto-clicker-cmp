package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Strict Android mutual-TLS transport. Its context must be built from verified trusted peers. */
class AndroidTlsTransport(private val sslContext: SSLContext) {
    suspend fun listen(port: Int = 0, requireClientAuthentication: Boolean = true): AndroidTlsListener = withContext(Dispatchers.IO) {
        val server = sslContext.serverSocketFactory.createServerSocket(port) as SSLServerSocket
        server.enabledProtocols = arrayOf(TLS_1_3)
        server.needClientAuth = requireClientAuthentication
        AndroidTlsListener(server)
    }

    suspend fun connect(endpoint: PeerEndpoint, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): AndroidTlsConnection =
        withContext(Dispatchers.IO) {
            require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS)
            val socket = sslContext.socketFactory.createSocket() as SSLSocket
            try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMillis)
                socket.soTimeout = timeoutMillis
                socket.enabledProtocols = arrayOf(TLS_1_3)
                socket.startHandshake()
                socket.soTimeout = TransferTimeouts.SESSION_READ_MILLIS
                AndroidTlsConnection(socket)
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
        }

    private companion object {
        const val TLS_1_3 = "TLSv1.3"
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val MAX_TIMEOUT_MILLIS = 60_000
    }
}

class AndroidTlsListener internal constructor(private val socket: SSLServerSocket) : AutoCloseable {
    val endpoint: ListeningEndpoint get() = ListeningEndpoint(socket.localPort)

    suspend fun accept(): AndroidTlsConnection = withContext(Dispatchers.IO) {
        val accepted = socket.accept() as SSLSocket
        try {
            accepted.soTimeout = TransferTimeouts.HANDSHAKE_MILLIS
            accepted.enabledProtocols = arrayOf("TLSv1.3")
            accepted.startHandshake()
            accepted.soTimeout = TransferTimeouts.SESSION_READ_MILLIS
            AndroidTlsConnection(accepted)
        } catch (failure: Throwable) {
            runCatching { accepted.close() }
            throw failure
        }
    }

    override fun close() = socket.close()
}

class AndroidTlsConnection internal constructor(private val socket: SSLSocket) : AutoCloseable {
    private val input = socket.inputStream
    private val output = socket.outputStream

    val peerCertificateFingerprint: String? = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest((socket.session.peerCertificates.first() as X509Certificate).encoded)
            .joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** DER of the peer certificate as presented on this connection (used by nearby pairing). */
    val peerCertificateDer: ByteArray? = runCatching { (socket.session.peerCertificates.first() as X509Certificate).encoded }.getOrNull()

    /** Remote IP address, for per-address pairing rate limits. */
    val remoteAddress: String = socket.inetAddress?.hostAddress.orEmpty()

    fun sendControl(payload: ByteArray) = AndroidTlsControlFrameCodec.write(output, payload)
    fun receiveControl(): ByteArray = AndroidTlsControlFrameCodec.read(input)
    fun dataInput(): InputStream = input
    fun dataOutput(): OutputStream = output

    /**
     * Main-safe. Closing a TLS socket writes close_notify, which Android's StrictMode forbids on
     * the main thread (cancel buttons and UI-scoped coroutines call this), so hand it off there.
     */
    override fun close() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) closeExecutor.execute(::closeNow) else closeNow()
    }

    private fun closeNow() {
        runCatching { socket.soTimeout = 1_000 }
        runCatching { socket.close() }
    }

    private companion object {
        val closeExecutor: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }
}

private object AndroidTlsControlFrameCodec {
    fun write(output: OutputStream, payload: ByteArray) {
        require(payload.size <= TransferProtocol.MAX_CONTROL_MESSAGE_BYTES)
        DataOutputStream(output).apply {
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    fun read(input: InputStream): ByteArray {
        val size = DataInputStream(input).readInt()
        require(size in 0..TransferProtocol.MAX_CONTROL_MESSAGE_BYTES) { "Invalid control frame size." }
        return ByteArray(size).also { bytes ->
            try {
                DataInputStream(input).readFully(bytes)
            } catch (failure: EOFException) {
                throw IllegalArgumentException("Incomplete control frame.", failure)
            }
        }
    }
}
