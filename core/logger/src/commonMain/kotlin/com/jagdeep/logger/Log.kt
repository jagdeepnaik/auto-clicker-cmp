package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig

object Log {

    private var isDeviceDisplayOnEnabled: Boolean = false
    private var isHarCapturePluginEnabled: Boolean = true

    fun isConsoleLoggingEnabled(): Boolean = ConsoleLogging.isEnabled

    fun isFileLoggingEnabled(): Boolean = LogFileRecorder.isEnabled

    fun isHarLoggingEnabled(): Boolean = HarLogRecorder.isEnabled

    fun isHarCapturePluginEnabled(): Boolean = isHarCapturePluginEnabled

    fun isDiagnosticLoggingEnabled(): Boolean {
        return LogFileRecorder.isEnabled && HarLogRecorder.isEnabled
    }

    fun isKeepDeviceDisplayOnEnabled(): Boolean = isDeviceDisplayOnEnabled

    fun setConsoleLoggingEnabled(isEnabled: Boolean) {
        ConsoleLogging.setEnabled(isEnabled)
    }

    fun setFileLoggingEnabled(isEnabled: Boolean) {
        val wasEnabled = LogFileRecorder.isEnabled
        LogFileRecorder.setEnabled(isEnabled)
        if (isEnabled && !wasEnabled) {
            logStartupDiagnostics()
        }
    }

    fun setHarLoggingEnabled(isEnabled: Boolean) {
        HarLogRecorder.setEnabled(isEnabled)
    }

    fun setHarCapturePluginEnabled(isEnabled: Boolean) {
        isHarCapturePluginEnabled = isEnabled
    }

    fun setKeepDeviceDisplayOnEnabled(isEnabled: Boolean) {
        this.isDeviceDisplayOnEnabled = isEnabled
    }

    fun setDiagnosticLoggingEnabled(isEnabled: Boolean) {
        setHarLoggingEnabled(isEnabled)
        setFileLoggingEnabled(isEnabled)
    }

    fun clearDiagnosticLogs() {
        LogFileRecorder.clear()
        HarLogRecorder.clear()
    }

    /** Clears Zynk's current file-log buffer without affecting optional HAR logging. */
    fun clearFileLogs() {
        LogFileRecorder.clear()
    }

    fun d(msg: String, tag: String? = AppConfig.APP_NAME) {
        print(message = msg, tag = tag, level = Level.DEBUG)
    }

    fun i(msg: String, tag: String? = AppConfig.APP_NAME) {
        print(message = msg, tag = tag, level = Level.INFO)
    }

    fun w(msg: String, tag: String? = AppConfig.APP_NAME) {
        print(message = msg, tag = tag, level = Level.WARN)
    }

    fun e(msg: String, tag: String? = AppConfig.APP_NAME) {
        print(message = msg, tag = tag, level = Level.ERROR)
    }

    private fun logStartupDiagnostics() {
        LogFileRecorder.record(Level.INFO, "Diagnostics", "File logging started")
        startupDiagnosticInfoLines { lines ->
            lines.forEach { line ->
                LogFileRecorder.record(Level.INFO, "Diagnostics", line)
            }
        }
    }

    fun print(message: String, tag: String? = AppConfig.APP_NAME, level: Level = Level.INFO) {
        val safeMessage = DiagnosticRedactor.redact(message)
        ConsoleLogging.print(message = safeMessage, tag = tag, level = level)
        LogFileRecorder.record(level = level, tag = tag, message = safeMessage)
    }

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}
