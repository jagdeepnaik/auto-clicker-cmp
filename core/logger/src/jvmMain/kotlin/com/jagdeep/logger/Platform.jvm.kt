package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import java.awt.Desktop
import java.io.File
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone

actual fun initLogger(logConfig: LogConfig, context: Any?) {
    Log.setConsoleLoggingEnabled(logConfig.consoleLoggingEnabled)
    Log.setHarLoggingEnabled(logConfig.harLoggingEnabled)
    Log.setHarCapturePluginEnabled(logConfig.harCapturePluginEnabled)
    Log.setKeepDeviceDisplayOnEnabled(logConfig.keepDeviceDisplayOn)
    Log.setFileLoggingEnabled(logConfig.fileLoggingEnabled)
    JvmCrashLogger.install()

    Napier.base(DebugAntilog())
}

private object JvmCrashLogger {
    private var isInstalled = false

    fun install() {
        if (isInstalled) return
        isInstalled = true
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeLogFile(
                    fileName = "crash-${System.currentTimeMillis()}.log",
                    content = DiagnosticRedactor.redact(
                        buildString {
                            appendLine("Zynk crash report")
                            appendLine("createdAtMillis=${System.currentTimeMillis()}")
                            appendLine("thread=${thread.name}")
                            appendLine()
                            appendLine("Stack trace")
                            appendLine(throwable.stackTraceToString())
                        },
                    ),
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }
}

fun initLoggerJvm() = initLogger(logConfig = LogConfig(), context = null)

actual fun startupDiagnosticInfoLines(onLines: (List<String>) -> Unit) {
    onLines(
        listOf(
            "app.name=${AppConfig.APP_NAME}",
            "app.versionName=${AppConfig.APP_VERSION_NAME}",
            "app.versionCode=${AppConfig.APP_VERSION_CODE}",
            "logger.consoleEnabled=${Log.isConsoleLoggingEnabled()}",
            "logger.fileEnabled=${Log.isFileLoggingEnabled()}",
            "logger.harEnabled=${Log.isHarLoggingEnabled()}",
            "logger.harCaptureEnabled=${Log.isHarCapturePluginEnabled()}",
            "logger.keepDisplayOnEnabled=${Log.isKeepDeviceDisplayOnEnabled()}",
            "jvm.javaVersion=${System.getProperty("java.version")}",
            "jvm.vendor=${System.getProperty("java.vendor")}",
            "os.name=${System.getProperty("os.name")}",
            "os.version=${System.getProperty("os.version")}",
            "os.arch=${System.getProperty("os.arch")}",
            "locale=${Locale.getDefault()}",
            "timeZone=${TimeZone.getDefault().id}",
            "network.localAddresses=${localNetworkAddresses()}",
        )
    )
}

private fun localNetworkAddresses(): String =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { networkInterface ->
                networkInterface.isUp &&
                        !networkInterface.isLoopback &&
                        !networkInterface.isVirtual
            }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .toList()
                    .asSequence()
                    .filter { address ->
                        !address.isAnyLocalAddress &&
                                !address.isLoopbackAddress &&
                                !address.isLinkLocalAddress
                    }
                    .map { address -> "${networkInterface.name}/${address.hostAddress}" }
            }
            .distinct()
            .sorted()
            .joinToString()
            .ifBlank { "unknown" }
    }.getOrDefault("unknown")

actual fun writeHarFile(fileName: String, content: String): String {
    val directory = File(getAppDataDirectory(), "har").apply { mkdirs() }
    val file = File(directory, fileName)
    file.writeText(content)
    return file.absolutePath
}

actual fun writeLogFile(fileName: String, content: String): String {
    val directory = File(getAppDataDirectory(), "logs").apply { mkdirs() }
    val file = File(directory, fileName)
    file.writeText(content)
    return file.absolutePath
}

actual fun listDiagnosticLogFiles(): List<DiagnosticLogFile> =
    listOf(
        File(getAppDataDirectory(), "logs"),
        File(getAppDataDirectory(), "har"),
    ).flatMap { directory -> directory.diagnosticFiles() }
        .sortedByDescending { it.modifiedAtMillis }

actual fun deleteDiagnosticLogFiles(paths: List<String>): Int =
    paths.count { path ->
        runCatching {
            val file = File(path)
            file.isFile && file.delete()
        }.getOrDefault(false)
    }

actual fun revealDiagnosticLogFile(path: String): Boolean = runCatching {
    val file = File(path)
    val directory = file.parentFile ?: return false
    Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) && directory.isDirectory
        && run {
            Desktop.getDesktop().open(directory)
            true
        }
}.getOrDefault(false)

private fun File.diagnosticFiles(): List<DiagnosticLogFile> =
    listFiles()
        ?.asSequence()
        ?.filter { file -> file.isFile && file.extension.lowercase() in setOf("log", "har") }
        ?.map { file ->
            DiagnosticLogFile(
                name = file.name,
                path = file.absolutePath,
                sizeBytes = file.length(),
                modifiedAtMillis = file.lastModified(),
            )
        }
        ?.toList()
        .orEmpty()

private fun getAppDataDirectory(): File {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") ->
            File(System.getProperty("user.home"), "Library/Application Support/${AppConfig.APP_NAME}")

        os.contains("win") ->
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), AppConfig.APP_NAME)

        else ->
            File(System.getProperty("user.home"), ".local/share/${AppConfig.APP_NAME}")
    }
}
