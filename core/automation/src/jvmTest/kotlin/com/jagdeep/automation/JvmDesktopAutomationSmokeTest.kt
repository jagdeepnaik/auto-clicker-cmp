package com.jagdeep.automation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Exercises the real native bindings on the machine running the tests, without sending any input
 * or asking for permission. Only the read-only calls run here; clicking needs a person watching.
 */
class JvmDesktopAutomationSmokeTest {
    @Test
    fun `given this operating system when reading native state then the calls succeed`() {
        val automation = createDesktopAutomation()
        try {
            assertTrue(automation.access.access() in InputAccess.entries)
            // Reading must not throw, whatever the answer is on this machine.
            automation.apps.frontmost()
            automation.apps.running()
            automation.pointer.position()
        } finally {
            automation.close()
        }
    }

    @Test
    fun `given a Mac when asked to keep the display awake then macOS holds it once, until released`() {
        if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return
        val pid = ProcessHandle.current().pid().toString()
        fun helpers() = ProcessHandle.current().children().filter { it.info().command().orElse("").endsWith("caffeinate") }.toList()
        fun systemHolds(): Boolean {
            val process = ProcessBuilder("/usr/bin/pmset", "-g", "assertions").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            return output.lines().any { "caffeinate" in it && "PreventUserIdleDisplaySleep" in it && "asserting on behalf of Process" !in it } ||
                output.contains("caffeinate command-line tool")
        }
        fun waitUntil(condition: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 5_000
            while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        }
        val native = MacNativeInput()
        try {
            native.setDisplayAwake(true)
            native.setDisplayAwake(true)
            waitUntil { helpers().size == 1 && systemHolds() }
            assertTrue(helpers().size == 1, "Asking twice must hold it once, for process $pid")
            assertTrue(systemHolds(), "macOS should report the display being held")
        } finally {
            native.setDisplayAwake(false)
        }
        waitUntil { helpers().isEmpty() }
        assertTrue(helpers().isEmpty(), "Releasing must end the helper")
    }

    @Test
    fun `given a Mac when reading every hot key's state then the native binding answers`() {
        if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return
        val native = MacNativeInput()
        HotKey.entries.forEach { native.isKeyDown(it) }
    }
}
