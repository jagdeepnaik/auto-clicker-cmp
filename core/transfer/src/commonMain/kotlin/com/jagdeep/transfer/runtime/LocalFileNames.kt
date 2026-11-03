package com.jagdeep.transfer.runtime

/**
 * Maps a validated, forward-slash relative path to one the receiving filesystem accepts.
 * Names legal on macOS/Android can be illegal on Windows (`: * ? " < > |`, trailing dots or
 * spaces, reserved device names such as `CON`); a `:` can even create an NTFS alternate data
 * stream instead of a file. Path-safety validation (traversal, absolute paths) happens first.
 */
object LocalFileNames {
    private val windowsForbidden = setOf('<', '>', ':', '"', '|', '?', '*')
    private val windowsReserved = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    fun forLocalFilesystem(relativePath: String, isWindows: Boolean): String =
        if (!isWindows) relativePath else relativePath.split('/').joinToString("/") { windowsSegment(it) }

    private fun windowsSegment(segment: String): String {
        val replaced = segment.map { if (it in windowsForbidden || it.code < 32) '_' else it }.joinToString("")
        val trimmed = replaced.trimEnd('.', ' ').ifEmpty { "_" }
        val stem = trimmed.substringBefore('.').uppercase()
        return if (stem in windowsReserved) "_$trimmed" else trimmed
    }
}
