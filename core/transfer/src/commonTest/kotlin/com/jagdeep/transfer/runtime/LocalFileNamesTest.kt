package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class LocalFileNamesTest {
    @Test
    fun `given a Windows receiver when names contain forbidden characters then they become underscores`() {
        assertEquals("Meeting_ notes_.txt", LocalFileNames.forLocalFilesystem("Meeting: notes?.txt", isWindows = true))
        assertEquals("a_b/c_d.txt", LocalFileNames.forLocalFilesystem("a|b/c*d.txt", isWindows = true))
    }

    @Test
    fun `given a Windows receiver when names are reserved or end in dots then they are made usable`() {
        assertEquals("_CON.txt", LocalFileNames.forLocalFilesystem("CON.txt", isWindows = true))
        assertEquals("_nul", LocalFileNames.forLocalFilesystem("nul", isWindows = true))
        assertEquals("draft/final", LocalFileNames.forLocalFilesystem("draft. /final...", isWindows = true))
        assertEquals("CONSOLE.txt", LocalFileNames.forLocalFilesystem("CONSOLE.txt", isWindows = true))
    }

    @Test
    fun `given a macOS or Linux receiver when receiving then names are unchanged`() {
        assertEquals("Meeting: notes?.txt", LocalFileNames.forLocalFilesystem("Meeting: notes?.txt", isWindows = false))
    }
}
