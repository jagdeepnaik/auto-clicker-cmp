package com.jagdeep.logger

data class DiagnosticLogFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

expect fun initLogger(logConfig: LogConfig, context: Any? = null)

expect fun writeHarFile(fileName: String, content: String): String

expect fun writeLogFile(fileName: String, content: String): String

expect fun listDiagnosticLogFiles(): List<DiagnosticLogFile>

expect fun deleteDiagnosticLogFiles(paths: List<String>): Int

/** Reveals an exported diagnostic log in the platform file manager when supported. */
expect fun revealDiagnosticLogFile(path: String): Boolean

expect fun startupDiagnosticInfoLines(onLines: (List<String>) -> Unit)
