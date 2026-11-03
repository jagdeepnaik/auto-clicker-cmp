package com.jagdeep.network.connectivity

/** Result of a short external connectivity probe, separate from local network availability. */
enum class InternetConnectivity {
    AVAILABLE,
    UNAVAILABLE,
    UNKNOWN,
}

/**
 * Platform probe used when a network-dependent operation fails.
 * Implementations must use a short timeout and never throw to callers.
 */
expect object InternetConnectivityProbe {
    suspend fun probe(): InternetConnectivity
}
