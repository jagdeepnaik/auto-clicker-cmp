package com.jagdeep.logger

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone

internal object HarAndroidContext {
    var applicationContext: Context? = null
}

actual fun initLogger(logConfig: LogConfig, context: Any?) {
    HarAndroidContext.applicationContext = (context as? Context)?.applicationContext
    AndroidCrashLogger.install(HarAndroidContext.applicationContext)

    Log.setConsoleLoggingEnabled(logConfig.consoleLoggingEnabled)
    Log.setHarLoggingEnabled(logConfig.harLoggingEnabled)
    Log.setHarCapturePluginEnabled(logConfig.harCapturePluginEnabled)
    Log.setKeepDeviceDisplayOnEnabled(logConfig.keepDeviceDisplayOn)
    Log.setFileLoggingEnabled(logConfig.fileLoggingEnabled)

    Napier.base(DebugAntilog())
}

fun initLoggerAndroid(context: Context) = initLogger(
    logConfig = LogConfig(),
    context = context,
)

actual fun startupDiagnosticInfoLines(onLines: (List<String>) -> Unit) {
    Thread {
        val lines = collectAndroidStartupDiagnosticInfoLines(HarAndroidContext.applicationContext)
        Handler(Looper.getMainLooper()).post { onLines(lines) }
    }.start()
}

private fun collectAndroidStartupDiagnosticInfoLines(context: Context?): List<String> =
    buildList {
        add("app.name=${com.jagdeep.common.config.AppConfig.APP_NAME}")
        add("app.versionName=${com.jagdeep.common.config.AppConfig.APP_VERSION_NAME}")
        add("app.versionCode=${com.jagdeep.common.config.AppConfig.APP_VERSION_CODE}")
        add("app.packageName=${context?.packageName ?: "unknown"}")
        add("logger.consoleEnabled=${Log.isConsoleLoggingEnabled()}")
        add("logger.fileEnabled=${Log.isFileLoggingEnabled()}")
        add("logger.harEnabled=${Log.isHarLoggingEnabled()}")
        add("logger.harCaptureEnabled=${Log.isHarCapturePluginEnabled()}")
        add("logger.keepDisplayOnEnabled=${Log.isKeepDeviceDisplayOnEnabled()}")
        add("android.sdk=${Build.VERSION.SDK_INT}")
        add("android.release=${Build.VERSION.RELEASE}")
        add("android.codename=${Build.VERSION.CODENAME}")
        add("device.manufacturer=${Build.MANUFACTURER}")
        add("device.brand=${Build.BRAND}")
        add("device.model=${Build.MODEL}")
        add("device.device=${Build.DEVICE}")
        add("device.product=${Build.PRODUCT}")
        add("device.hardware=${Build.HARDWARE}")
        add("device.supportedAbis=${Build.SUPPORTED_ABIS.joinToString()}")
        add("locale=${Locale.getDefault()}")
        add("timeZone=${TimeZone.getDefault().id}")
        addAll(context.networkDiagnosticInfoLines())
        add("network.localAddresses=${localNetworkAddresses()}")
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

private fun Context?.networkDiagnosticInfoLines(): List<String> {
    val context = this ?: return listOf("network=unknown")
    val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return listOf("network=unknown")
    val activeNetwork = connectivityManager.activeNetwork
        ?: return listOf("network.active=false")
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        ?: return listOf("network.active=true", "network.capabilities=unknown")
    return listOf(
        "network.active=true",
        "network.validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}",
        "network.captivePortal=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)}",
        "network.wifi=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}",
        "network.cellular=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)}",
        "network.vpn=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}",
        "network.ethernet=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)}",
    )
}

actual fun writeHarFile(fileName: String, content: String): String {
    val context = requireNotNull(HarAndroidContext.applicationContext) {
        "Logger is not initialized with Android application context."
    }
    val directory = File(context.filesDir, "har").apply { mkdirs() }
    val file = File(directory, fileName)
    file.writeText(content)
    return file.absolutePath
}

actual fun writeLogFile(fileName: String, content: String): String {
    val context = requireNotNull(HarAndroidContext.applicationContext) {
        "Logger is not initialized with Android application context."
    }
    val directory = File(context.filesDir, "logs").apply { mkdirs() }
    val file = File(directory, fileName)
    file.writeText(content)
    return file.absolutePath
}

actual fun listDiagnosticLogFiles(): List<DiagnosticLogFile> {
    val context = HarAndroidContext.applicationContext ?: return emptyList()
    return listOf(
        File(context.filesDir, "logs"),
        File(context.filesDir, "har"),
    ).flatMap { directory -> directory.diagnosticFiles() }
        .sortedByDescending { it.modifiedAtMillis }
}

actual fun deleteDiagnosticLogFiles(paths: List<String>): Int =
    paths.count { path ->
        runCatching {
            val file = File(path)
            file.isFile && file.delete()
        }.getOrDefault(false)
    }

actual fun revealDiagnosticLogFile(path: String): Boolean = false

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

private object AndroidCrashLogger {
    private var isInstalled = false

    fun install(context: Context?) {
        val applicationContext = context?.applicationContext ?: return
        if (isInstalled) return
        isInstalled = true

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                writeCrashLog(
                    context = applicationContext,
                    thread = thread,
                    throwable = throwable,
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val directory = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(directory, "crash-${System.currentTimeMillis()}.log")
        file.writeText(
            DiagnosticRedactor.redact(
                buildString {
                appendLine("Zynk crash report")
                appendLine("createdAtMillis=${System.currentTimeMillis()}")
                appendLine("thread=${thread.name}")
                appendLine()
                appendLine("Diagnostics")
                collectAndroidStartupDiagnosticInfoLines(context).forEach { line ->
                    appendLine(line)
                }
                appendLine()
                appendLine("Stack trace")
                appendLine(throwable.stackTraceText())
                }
            )
        )
    }

    private fun Throwable.stackTraceText(): String =
        StringWriter().use { writer ->
            PrintWriter(writer).use { printWriter ->
                printStackTrace(printWriter)
                writer.toString()
            }
        }
}
