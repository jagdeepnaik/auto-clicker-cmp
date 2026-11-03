package com.jagdeep.ui.util

/** Human-readable binary size, e.g. `2.1 MB`. Whole bytes are shown without a decimal. */
fun Long.toReadableBytes(): String {
    if (this < 1024) return "$this B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = this / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    val rounded = (value * 10).toLong() / 10.0
    val text = if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    return "$text ${units[unitIndex]}"
}

/** Transfer rate such as `850 KB/s` or `12.4 MB/s`. */
fun Double.toReadableRate(): String = if (this <= 0.0) "— /s" else "${toLong().toReadableBytes()}/s"
