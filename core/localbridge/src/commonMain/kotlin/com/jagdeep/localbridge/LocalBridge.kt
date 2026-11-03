package com.jagdeep.localbridge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * A channel to one trusted local client, such as a browser extension, on this computer only. The
 * app publishes its latest state as JSON and receives short messages; it doesn't interpret them.
 */
interface LocalBridge {
    /** True while the client is checking in. */
    val connected: StateFlow<Boolean>

    /** Why the bridge couldn't start, or null. */
    val problem: StateFlow<String?>

    /** Message bodies sent by the client. */
    val incoming: Flow<String>

    /** Replaces the state the client sees. [stateJson] must be one JSON value. */
    fun publish(stateJson: String)

    fun close()
}

/** Listens on loopback at [port] and answers only [allowedOrigin], or an extension request with no Origin. */
expect fun createLocalBridge(port: Int, allowedOrigin: String): LocalBridge

/** For platforms without a local client. */
class UnsupportedLocalBridge : LocalBridge {
    override val connected: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    override val problem: StateFlow<String?> = MutableStateFlow<String?>(null).asStateFlow()
    override val incoming: Flow<String> = emptyFlow()

    override fun publish(stateJson: String) = Unit

    override fun close() = Unit
}
