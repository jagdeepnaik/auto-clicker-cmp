package com.jagdeep.logger

import io.github.aakira.napier.DebugAntilog
import io.github.aakira.napier.Napier
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVarOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.fclose
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.posix.getnameinfo
import platform.darwin.ifaddrs
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.socklen_t
import platform.posix.fopen
import platform.posix.fputs

actual fun initLogger(logConfig: LogConfig, context: Any?) {
    Log.setConsoleLoggingEnabled(logConfig.consoleLoggingEnabled)
    Log.setHarLoggingEnabled(logConfig.harLoggingEnabled)
    Log.setHarCapturePluginEnabled(logConfig.harCapturePluginEnabled)
    Log.setKeepDeviceDisplayOnEnabled(logConfig.keepDeviceDisplayOn)
    Log.setFileLoggingEnabled(logConfig.fileLoggingEnabled)

    Napier.base(DebugAntilog())
}

fun initLoggerIos() = initLogger(logConfig = LogConfig(), context = null)

actual fun startupDiagnosticInfoLines(onLines: (List<String>) -> Unit) {
    val device = UIDevice.currentDevice
    onLines(
        listOf(
            "app.name=${com.jagdeep.common.config.AppConfig.APP_NAME}",
            "app.versionName=${com.jagdeep.common.config.AppConfig.APP_VERSION_NAME}",
            "app.versionCode=${com.jagdeep.common.config.AppConfig.APP_VERSION_CODE}",
            "logger.consoleEnabled=${Log.isConsoleLoggingEnabled()}",
            "logger.fileEnabled=${Log.isFileLoggingEnabled()}",
            "logger.harEnabled=${Log.isHarLoggingEnabled()}",
            "logger.harCaptureEnabled=${Log.isHarCapturePluginEnabled()}",
            "logger.keepDisplayOnEnabled=${Log.isKeepDeviceDisplayOnEnabled()}",
            "ios.systemName=${device.systemName}",
            "ios.systemVersion=${device.systemVersion}",
            "ios.model=${device.model}",
            "ios.localizedModel=${device.localizedModel}",
            "ios.identifierForVendorAvailable=${device.identifierForVendor != null}",
            "network.localAddresses=${localNetworkAddresses()}",
        )
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun localNetworkAddresses(): String = memScoped {
    val interfacePointer = alloc<CPointerVarOf<CPointer<ifaddrs>>>()
    if (getifaddrs(interfacePointer.ptr) != 0) {
        return@memScoped "unknown"
    }

    val addresses = mutableListOf<String>()
    var current = interfacePointer.value
    try {
        while (current != null) {
            val networkInterface = current.pointed
            val address = networkInterface.ifa_addr
            val flags = networkInterface.ifa_flags.toInt()
            val family = address?.pointed?.sa_family?.toInt()
            if (address != null &&
                flags and IFF_UP != 0 &&
                flags and IFF_LOOPBACK == 0 &&
                (family == AF_INET || family == AF_INET6)
            ) {
                val host = allocArray<ByteVar>(NI_MAXHOST)
                val addressLength: socklen_t = when (family) {
                    AF_INET -> kotlinx.cinterop.sizeOf<sockaddr_in>().convert()
                    else -> kotlinx.cinterop.sizeOf<sockaddr_in6>().convert()
                }
                if (getnameinfo(
                        address,
                        addressLength,
                        host,
                        NI_MAXHOST.convert(),
                        null,
                        0u,
                        NI_NUMERICHOST,
                    ) == 0
                ) {
                    val value = host.toKString()
                    if (!value.startsWith("169.254.") &&
                        !value.startsWith("fe80:", ignoreCase = true)
                    ) {
                        val name = networkInterface.ifa_name?.toKString() ?: "unknown"
                        addresses += "$name/$value"
                    }
                }
            }
            current = networkInterface.ifa_next
        }
    } finally {
        freeifaddrs(interfacePointer.value)
    }

    addresses.distinct().sorted().joinToString().ifBlank { "unknown" }
}

@OptIn(ExperimentalForeignApi::class)
actual fun writeHarFile(fileName: String, content: String): String =
    writeFile(fileName = fileName, content = content, description = "HAR")

@OptIn(ExperimentalForeignApi::class)
actual fun writeLogFile(fileName: String, content: String): String =
    writeFile(fileName = fileName, content = content, description = "log")

@OptIn(ExperimentalForeignApi::class)
private fun writeFile(fileName: String, content: String, description: String): String {
    val directory = documentsDirectoryPath()
    val path = "$directory/$fileName"
    val file = fopen(path, "w") ?: error("Unable to create $description file at $path")
    fputs(content, file)
    fclose(file)
    return path
}

@OptIn(ExperimentalForeignApi::class)
actual fun listDiagnosticLogFiles(): List<DiagnosticLogFile> {
    val directory = documentsDirectoryPath()
    val fileManager = NSFileManager.defaultManager
    val names = fileManager.contentsOfDirectoryAtPath(directory, error = null)
        ?.filterIsInstance<String>()
        .orEmpty()

    return names
        .asSequence()
        .filter { name -> name.substringAfterLast('.', "").lowercase() in setOf("log", "har") }
        .mapNotNull { name ->
            val path = "$directory/$name"
            val attributes = fileManager.attributesOfItemAtPath(path, error = null)
                ?: return@mapNotNull null
            val size = (attributes[NSFileSize] as? Number)?.toLong() ?: 0L
            DiagnosticLogFile(
                name = name,
                path = path,
                sizeBytes = size,
                modifiedAtMillis = 0L,
            )
        }
        .sortedByDescending { it.name }
        .toList()
}

@OptIn(ExperimentalForeignApi::class)
actual fun deleteDiagnosticLogFiles(paths: List<String>): Int {
    val fileManager = NSFileManager.defaultManager
    return paths.count { path ->
        fileManager.fileExistsAtPath(path) &&
                fileManager.removeItemAtPath(path, error = null)
    }
}

actual fun revealDiagnosticLogFile(path: String): Boolean = false

@OptIn(ExperimentalForeignApi::class)
private fun documentsDirectoryPath(): String =
    NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    )?.path ?: "."
