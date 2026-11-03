package com.jagdeep.autoclicker

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BrowserExtensionTest {
    private fun bundled(name: String) = checkNotNull(javaClass.getResourceAsStream("/chrome-extension/$name")) { "Missing $name" }
        .bufferedReader().readText()

    @Test
    fun `given the manifest key when deriving the id then it matches the origin the app trusts`() {
        val key = Regex("\"key\": \"([^\"]+)\"").find(bundled("manifest.json"))!!.groupValues[1]
        val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(key))
        val id = digest.take(16).joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            "${'a' + (value shr 4)}${'a' + (value and 0xF)}"
        }
        assertEquals(BROWSER_EXTENSION_ID, id)
    }

    @Test
    fun `given the port when read by each side then they agree`() {
        assertTrue("127.0.0.1:$BROWSER_LINK_PORT" in bundled("manifest.json"))
        assertTrue("127.0.0.1:$BROWSER_LINK_PORT" in bundled("background.js"))
    }

    @Test
    fun `given the app bundle when writing the extension then every file arrives`() {
        val folder = Files.createTempDirectory("extension").toFile()
        try {
            writeBrowserExtension(folder)
            assertEquals(BROWSER_EXTENSION_FILES.toSet(), folder.list()!!.toSet())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `given the extension's permissions when read then it can only reach this app`() {
        assertTrue("\"host_permissions\": [\"http://127.0.0.1:47613/*\"]" in bundled("manifest.json"))
        val urls = Regex("https?://[^\"`\\s]+").findAll(bundled("background.js") + bundled("content.js")).map { it.value }.toSet()
        assertEquals(setOf("http://127.0.0.1:47613"), urls)
    }
}
