package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig
import com.jagdeep.common.getPlatform
import com.jagdeep.common.time.AppClock

/**
 * Creates a privacy-reviewed diagnostic payload for a future in-app support flow.
 *
 * This deliberately uses file logging only. HAR remains available as a separate generic logger
 * capability, but is not part of Zynk support diagnostics. This builder never changes logger
 * enablement; file logs are included only when they are already enabled.
 */
object DiagnosticReportBuilder {
    private const val MaxLogCharacters = 250_000

    fun build(): DiagnosticReportBundle {
        val platform = getPlatform()
        val attachments = buildList {
            if (Log.isFileLoggingEnabled()) {
                add(
                    DiagnosticReportAttachment(
                        name = "${AppConfig.APP_NAME_LOWERCASE}-diagnostic.log",
                        contentType = "text/plain",
                        content = redactAndLimit(LogFileRecorder.exportLogText(), MaxLogCharacters),
                    )
                )
            }
        }

        return DiagnosticReportBundle(
            createdAtMillis = AppClock.nowMillis(),
            appName = AppConfig.APP_NAME,
            appVersionName = AppConfig.APP_VERSION_NAME,
            appVersionCode = AppConfig.APP_VERSION_CODE,
            platformName = platform.name,
            platformVersion = platform.version,
            platformType = platform.type.name,
            attachments = attachments,
        )
    }

    /**
     * Writes the redacted diagnostic report through the existing platform file logger.
     * Returns null when file logging is not currently enabled, leaving that choice to the UI.
     */
    fun exportFile(): String? {
        val bundle = build()
        val logAttachment = bundle.attachments.singleOrNull() ?: return null
        return writeLogFile(
            fileName = "${AppConfig.APP_NAME_LOWERCASE}-diagnostic-${bundle.createdAtMillis}.log",
            content = bundle.asText(logAttachment.content),
        )
    }

    internal fun redactAndLimit(value: String, maximumCharacters: Int): String {
        val redacted = DiagnosticRedactor.redact(value)

        return if (redacted.length <= maximumCharacters) {
            redacted
        } else {
            "[Older diagnostic content omitted]\\n" + redacted.takeLast(maximumCharacters)
        }
    }
}

private fun DiagnosticReportBundle.asText(logContent: String): String = buildString {
    appendLine("$appName diagnostic report")
    appendLine("createdAtMillis=$createdAtMillis")
    appendLine("version=$appVersionName ($appVersionCode)")
    appendLine("platform=$platformName $platformVersion ($platformType)")
    appendLine()
    append(logContent)
}

data class DiagnosticReportBundle(
    val createdAtMillis: Long,
    val appName: String,
    val appVersionName: String,
    val appVersionCode: String,
    val platformName: String,
    val platformVersion: String,
    val platformType: String,
    val attachments: List<DiagnosticReportAttachment>,
)

data class DiagnosticReportAttachment(
    val name: String,
    val contentType: String,
    val content: String,
)
