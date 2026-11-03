package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferManifest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Prepares the folder for a folder-sync transfer (see FOLDER_SYNC_PLAN.md). A sync transfer is
 * confined to `<receive folder>/Zynk Sync/<name>`; it is the only kind of transfer that may
 * replace files, and only after moving the existing file to `.zynk-trash/<time>/` in that
 * folder. Mirror removals are moved to the same trash, never deleted.
 */
object JvmSyncDestination {
    const val SYNC_FOLDER = "Zynk Sync"
    const val TRASH_FOLDER = ".zynk-trash"

    /** Returns the folder to write [manifest]'s files into, after trashing what they replace. */
    fun prepare(receiveRoot: Path, manifest: TransferManifest, nowEpochMillis: Long = System.currentTimeMillis()): Path {
        val sync = requireNotNull(manifest.sync) { "Not a sync transfer." }
        val base = receiveRoot.toAbsolutePath().normalize()
        val root = base.resolve(SYNC_FOLDER).resolve(sync.name).normalize()
        require(root.startsWith(base)) { "Sync folder escapes the receive folder." }
        Files.createDirectories(root)
        val realRoot = root.toRealPath()
        val trashRoot = root.resolve(TRASH_FOLDER)
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(nowEpochMillis))
        val trash = trashRoot.resolve(stamp)
        (manifest.entries.map { it.relativePath } + sync.remove).distinct().forEach { relative ->
            val target = root.resolve(relative).normalize()
            require(target.startsWith(root) && !target.startsWith(trashRoot)) { "Sync path is outside the sync folder." }
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@forEach
            // Refuse to follow links that lead outside the sync folder.
            require(target.parent.toRealPath().startsWith(realRoot)) { "Sync path is outside the sync folder." }
            require(!Files.isSymbolicLink(target)) { "Sync will not replace a link." }
            val moved = trash.resolve(relative)
            Files.createDirectories(moved.parent)
            Files.move(target, moved)
        }
        return root
    }
}
