package com.jagdeep.autoclicker

import com.jagdeep.home.domain.RoutineFileCodec
import com.jagdeep.home.domain.RoutineFileException
import com.jagdeep.home.domain.RoutineFiles
import com.jagdeep.logger.Log
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

actual fun createRoutineFiles(): RoutineFiles = DesktopRoutineFiles()

/** The system's own Save and Open panels (native on a Mac), with the file read and written off the UI thread. */
internal class DesktopRoutineFiles : RoutineFiles {
    override suspend fun save(suggestedName: String, contents: String): String? {
        val file = choose(FileDialog.SAVE, "Export routines", suggestedName) ?: return null
        // The panel may drop the double extension; the file should still say what it is.
        val target = if (file.name.endsWith(".json")) file else File(file.parentFile, file.name + RoutineFileCodec.FILE_SUFFIX)
        withContext(Dispatchers.IO) {
            try {
                target.writeText(contents)
            } catch (e: IOException) {
                Log.w("Export failed: ${e.message}", TAG)
                throw RoutineFileException("Couldn't save to that folder. Choose another one.")
            }
        }
        return target.absolutePath
    }

    override suspend fun open(): String? {
        val file = choose(FileDialog.LOAD, "Import routines", null) ?: return null
        return withContext(Dispatchers.IO) {
            if (file.length() > RoutineFileCodec.MAX_FILE_CHARACTERS * 4L) throw RoutineFileException("That file is too large to be a routine file.")
            try {
                file.readText()
            } catch (e: IOException) {
                Log.w("Import failed: ${e.message}", TAG)
                throw RoutineFileException("Couldn't read that file.")
            }
        }
    }

    /** Blocks the UI while open, as a modal panel should; returns null when cancelled. */
    private suspend fun choose(mode: Int, title: String, suggestedName: String?): File? = withContext(Dispatchers.Swing) {
        val dialog = FileDialog(null as Frame?, title, mode)
        suggestedName?.let { dialog.file = it }
        if (mode == FileDialog.LOAD) dialog.setFilenameFilter { _, name -> name.endsWith(".json") }
        dialog.isVisible = true
        val name = dialog.file ?: return@withContext null
        File(dialog.directory, name)
    }

    private companion object {
        const val TAG = "RoutineFiles"
    }
}
