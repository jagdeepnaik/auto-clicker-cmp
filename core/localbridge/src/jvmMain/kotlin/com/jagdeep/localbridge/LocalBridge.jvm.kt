package com.jagdeep.localbridge

import com.jagdeep.logger.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

actual fun createLocalBridge(port: Int, allowedOrigin: String): LocalBridge = JvmLocalBridge(port, allowedOrigin)

/**
 * Plain HTTP on loopback with long polling, using only the JDK. Web pages can't pass the check: the
 * browser sets the headers it relies on and scripts can't forge them, so no pairing secret is stored.
 */
internal class JvmLocalBridge(
    port: Int,
    private val allowedOrigin: String,
    private val pollHoldMillis: Long = POLL_HOLD_MILLIS,
) : LocalBridge {
    private val lock = Object()
    private var version = 0L
    private var state = "null"
    private var closed = false
    private var waitingPolls = 0
    private var lastSeenMillis = 0L

    private val mutableConnected = MutableStateFlow(false)
    private val mutableProblem = MutableStateFlow<String?>(null)
    private val mutableIncoming = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "local-bridge").apply { isDaemon = true } }
    private val server: HttpServer? = startServer(port)

    override val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    override val problem: StateFlow<String?> = mutableProblem.asStateFlow()
    override val incoming: Flow<String> = mutableIncoming.asSharedFlow()

    init {
        scope.launch {
            while (isActive) {
                mutableConnected.value = synchronized(lock) {
                    waitingPolls > 0 || System.currentTimeMillis() - lastSeenMillis < CONNECTED_GRACE_MILLIS
                }
                delay(CONNECTED_CHECK_MILLIS)
            }
        }
    }

    /** The address actually bound; tests pass port 0. */
    val boundPort: Int? get() = server?.address?.port

    override fun publish(stateJson: String) = synchronized(lock) {
        state = stateJson
        version++
        lock.notifyAll()
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
        server?.stop(0)
        executor.shutdownNow()
        scope.cancel()
    }

    private fun startServer(port: Int): HttpServer? = try {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
            executor = this@JvmLocalBridge.executor
            createContext("/state", ::handleState)
            createContext("/event", ::handleEvent)
            start()
        }
    } catch (e: IOException) {
        Log.w("Local bridge unavailable: ${e.message}", TAG)
        mutableProblem.value = "Port $port is in use by another app. Close it, then reopen this app."
        null
    }

    private fun handleState(exchange: HttpExchange) = exchange.use {
        if (!isAllowed(exchange) || exchange.requestMethod != "GET") return@use reject(exchange)
        val after = exchange.requestURI.query.orEmpty()
            .split('&').firstOrNull { it.startsWith("after=") }?.substringAfter('=')?.toLongOrNull() ?: -1L
        val body = synchronized(lock) {
            waitingPolls++
            lastSeenMillis = System.currentTimeMillis()
            try {
                val deadline = System.currentTimeMillis() + pollHoldMillis
                // Not-equal, so a client still holding a version from before a restart catches up.
                while (version == after && !closed) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
                lastSeenMillis = System.currentTimeMillis()
                """{"version":$version,"state":$state}"""
            } finally {
                waitingPolls--
            }
        }
        respond(exchange, 200, body)
    }

    private fun handleEvent(exchange: HttpExchange) = exchange.use {
        if (!isAllowed(exchange) || exchange.requestMethod != "POST") return@use reject(exchange)
        val bytes = exchange.requestBody.readNBytes(MAX_EVENT_BYTES + 1)
        if (bytes.size > MAX_EVENT_BYTES) return@use respond(exchange, 413, "")
        synchronized(lock) { lastSeenMillis = System.currentTimeMillis() }
        mutableIncoming.tryEmit(bytes.decodeToString())
        respond(exchange, 204, "")
    }

    /**
     * Chrome sends no Origin on an extension's GET to a host it has permission for, marking it
     * Sec-Fetch-Site: none instead. Pages can't set these headers, and their requests carry their
     * own Origin or say cross-site, same-site or same-origin, so they never match.
     */
    private fun isAllowed(exchange: HttpExchange): Boolean {
        val headers = exchange.requestHeaders
        val origin = headers.getFirst("Origin") ?: return headers.getFirst("Sec-Fetch-Site") == "none" && headers.getFirst("Sec-Fetch-Mode") == "cors"
        return origin == allowedOrigin
    }

    private fun reject(exchange: HttpExchange) = respond(exchange, 403, "")

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        if (isAllowed(exchange)) exchange.responseHeaders.add("Access-Control-Allow-Origin", allowedOrigin)
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val TAG = "LocalBridge"
        const val POLL_HOLD_MILLIS = 20_000L
        const val CONNECTED_GRACE_MILLIS = 5_000L
        const val CONNECTED_CHECK_MILLIS = 1_000L
        const val MAX_EVENT_BYTES = 16 * 1024
    }
}
