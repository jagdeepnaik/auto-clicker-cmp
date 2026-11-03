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
    fun `given a Mac when reading every hot key's state then the native binding answers`() {
        if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return
        val native = MacNativeInput()
        HotKey.entries.forEach { native.isKeyDown(it) }
    }
}
