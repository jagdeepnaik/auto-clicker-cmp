package com.jagdeep.network.util

/** Lightweight check for user input that can be handled as an HTTP(S) URL. */
fun String.isLikelyHttpUrl(): Boolean = trim().let { value ->
    value.startsWith("https://", ignoreCase = true) || value.startsWith("http://", ignoreCase = true)
}
