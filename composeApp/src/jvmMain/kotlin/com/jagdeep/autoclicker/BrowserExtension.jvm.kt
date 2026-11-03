package com.jagdeep.autoclicker

import com.jagdeep.logger.Log
import java.awt.Desktop
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val BROWSER_EXTENSION_FILES = listOf("manifest.json", "background.js", "content.js")

/** Chrome loads an unpacked extension from a folder, so the app's bundled copy is written to one. */
internal fun writeBrowserExtension(folder: File) {
    folder.mkdirs()
    BROWSER_EXTENSION_FILES.forEach { name ->
        val resource = object {}.javaClass.getResourceAsStream("/chrome-extension/$name")
            ?: throw IOException("Missing $name")
        resource.use { input -> File(folder, name).outputStream().use { input.copyTo(it) } }
    }
}

actual suspend fun installBrowserExtension(): String? = withContext(Dispatchers.IO) {
    val folder = File(File(System.getProperty("user.home"), ".autoclicker"), "chrome-extension")
    try {
        writeBrowserExtension(folder)
    } catch (e: IOException) {
        Log.w("Extension copy failed: ${e.message}", "BrowserExtension")
        return@withContext "Couldn't create the extension folder. Check there's free disk space, then try again."
    }
    val shown = runCatching { Desktop.getDesktop().open(folder) }.isSuccess
    if (shown) null else "The extension is in ${folder.absolutePath}."
}
