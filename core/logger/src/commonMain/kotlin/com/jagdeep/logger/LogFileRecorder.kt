package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig
import com.jagdeep.common.time.AppClock

object LogFileRecorder {
    private const val MAX_LINES = 2_000
    private val lines = mutableListOf<String>()

    var isEnabled: Boolean = false
        private set

    fun setEnabled(isEnabled: Boolean) {
        this.isEnabled = isEnabled
        if (!isEnabled) {
            clear()
        }
    }

    fun clear() {
        lines.clear()
    }

    fun record(level: Log.Level, tag: String?, message: String, throwable: Throwable? = null) {
        if (!isEnabled) return

        val throwableText =
            throwable?.let { " | ${it::class.simpleName}: ${it.message.orEmpty()}" }.orEmpty()

        val safeMessage = DiagnosticRedactor.redact(message)
        lines += "${AppClock.nowInstant()} ${level.name.padEnd(5)} ${tag.orEmpty()}: $safeMessage$throwableText"
        if (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
    }

    fun exportLogText(): String = buildString {
        appendLine("${AppConfig.APP_NAME_LOWERCASE} log export")
        appendLine("createdAt=${AppClock.nowInstant()}")
        appendLine("entries=${lines.size}")
        appendLine()
        lines.forEach { appendLine(it) }
    }

    fun exportLogFile(): String {
        val fileName =
            "${AppConfig.APP_NAME_LOWERCASE}-${AppClock.nowMillis()}.log"
        return writeLogFile(fileName = fileName, content = exportLogText())
    }
}
