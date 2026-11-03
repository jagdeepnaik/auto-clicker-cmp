package com.jagdeep.network.connectivity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual object InternetConnectivityProbe {
    actual suspend fun probe(): InternetConnectivity = withContext(Dispatchers.IO) {
        runCatching {
            ProbeUrls.any { probeUrl ->
                runCatching {
                    val connection = URL(probeUrl).openConnection() as HttpURLConnection
                    connection.requestMethod = "HEAD"
                    connection.connectTimeout = TimeoutMillis
                    connection.readTimeout = TimeoutMillis
                    connection.instanceFollowRedirects = true
                    connection.responseCode in 100..599
                }.getOrDefault(false)
            }
        }.fold(
            onSuccess = { reachable -> if (reachable) InternetConnectivity.AVAILABLE else InternetConnectivity.UNAVAILABLE },
            onFailure = { InternetConnectivity.UNAVAILABLE },
        )
    }

    private val ProbeUrls = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
    )
    private const val TimeoutMillis = 4_000
}
