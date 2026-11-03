package com.jagdeep.transfer.runtime

import java.io.File
import java.util.Base64

/**
 * Small JVM boundary for operating-system protected secrets. It deliberately has no file,
 * preferences, or in-memory production fallback: desktop pairing remains unavailable if the
 * platform credential store cannot be used.
 */
interface JvmSecureSecretStore {
    fun read(account: String): String?
    fun write(account: String, value: String)
}

/** macOS Keychain implementation backed by Apple's `security` command-line tool. */
class MacOsKeychainSecretStore private constructor(
    private val executable: String,
    private val service: String,
) : JvmSecureSecretStore {
    override fun read(account: String): String? {
        val result = run("find-generic-password", "-s", service, "-a", account, "-w")
        return if (result.exitCode == 0) result.output.trimEnd().ifBlank { null } else null
    }

    override fun write(account: String, value: String) {
        require(value.isNotEmpty()) { "A secure value must not be empty." }
        val result = run(
            "add-generic-password", "-U", "-s", service, "-a", account, "-w", value,
        )
        check(result.exitCode == 0) { "macOS Keychain could not save the Zynk credential." }
    }

    private fun run(vararg arguments: String): CommandResult {
        val process = ProcessBuilder(listOf(executable) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return CommandResult(process.waitFor(), output)
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    companion object {
        fun createOrNull(service: String = "com.jagdeep.zynk"): MacOsKeychainSecretStore? {
            if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true)) return null
            val executable = File("/usr/bin/security")
            return executable.takeIf(File::canExecute)?.let { MacOsKeychainSecretStore(it.path, service) }
        }
    }
}

/**
 * Splits values too large for one OS credential across several, all still in the OS store
 * (for example, Windows Credential Manager limits a credential to 2,560 bytes). A small header credential
 * records the chunk count; values written before chunking existed are read unchanged.
 */
class ChunkedSecretStore(
    private val delegate: JvmSecureSecretStore,
    private val maxChunkChars: Int = 1_000,
) : JvmSecureSecretStore {
    init { require(maxChunkChars in 16..1_200) }

    override fun read(account: String): String? {
        val head = delegate.read(account) ?: return null
        if (!head.startsWith(HEADER)) return head
        val count = head.removePrefix(HEADER).toIntOrNull()?.takeIf { it in 1..MAX_CHUNKS } ?: return null
        val parts = (1..count).map { delegate.read(chunkAccount(account, it)) ?: return null }
        return parts.joinToString("")
    }

    override fun write(account: String, value: String) {
        require(value.isNotEmpty()) { "A secure value must not be empty." }
        if (value.length <= maxChunkChars && !value.startsWith(HEADER)) {
            delegate.write(account, value)
            return
        }
        val chunks = value.chunked(maxChunkChars)
        check(chunks.size <= MAX_CHUNKS) { "Secure value is too large." }
        // Chunks first, header last: a crash mid-write leaves the previous header (and value) intact.
        chunks.forEachIndexed { index, chunk -> delegate.write(chunkAccount(account, index + 1), chunk) }
        delegate.write(account, HEADER + chunks.size)
    }

    private fun chunkAccount(account: String, index: Int) = "$account#$index"

    private companion object {
        const val HEADER = "chunked:v1:"
        const val MAX_CHUNKS = 64
    }
}


/**
 * Windows secrets protected by DPAPI for the current user (CryptProtectData), stored as files.
 * Only the same Windows user on the same machine can decrypt them. Calls the OS API directly
 * through JNA: no PowerShell (passing a script to it from Java on Windows strips its quotes) and
 * no Credential Manager size limit. App-specific entropy keeps other apps of the same user from
 * decrypting the files with a plain DPAPI call.
 */
class WindowsDpapiSecretStore(private val directory: java.nio.file.Path) : JvmSecureSecretStore {
    override fun read(account: String): String? {
        val file = fileFor(account)
        if (!java.nio.file.Files.exists(file)) return null
        val plain = com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(
            java.nio.file.Files.readAllBytes(file), ENTROPY, FLAGS, null,
        )
        return plain.decodeToString().also { plain.fill(0) }
    }

    override fun write(account: String, value: String) {
        require(value.isNotEmpty()) { "A secure value must not be empty." }
        java.nio.file.Files.createDirectories(directory)
        val plain = value.encodeToByteArray()
        val protected = try {
            com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain, ENTROPY, FLAGS, "Zynk", null)
        } finally {
            plain.fill(0)
        }
        // Write then rename, so a crash never leaves a half-written secret.
        val temporary = directory.resolve(fileFor(account).fileName.toString() + ".tmp")
        java.nio.file.Files.write(temporary, protected)
        java.nio.file.Files.move(temporary, fileFor(account), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun fileFor(account: String): java.nio.file.Path {
        require(account.isNotBlank() && account.all { it.isLetterOrDigit() || it in "-_.#" }) { "Invalid secret name." }
        return directory.resolve("$account.dpapi")
    }

    private companion object {
        val ENTROPY = "zynk-desktop-secrets-v1".encodeToByteArray()
        const val FLAGS = 0x1 // CRYPTPROTECT_UI_FORBIDDEN: never show a prompt
    }
}
