package com.jagdeep.network.connectivity

/** iOS probing will be added alongside its downloader implementation; do not make retry decisions from it yet. */
actual object InternetConnectivityProbe {
    actual suspend fun probe(): InternetConnectivity = InternetConnectivity.UNKNOWN
}
