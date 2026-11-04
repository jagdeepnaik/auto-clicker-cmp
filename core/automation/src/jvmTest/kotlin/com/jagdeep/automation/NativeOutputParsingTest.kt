package com.jagdeep.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeOutputParsingTest {
    @Test
    fun `given a Windows executable when matched to an app name then Chrome matches and others don't`() {
        assertTrue(isExecutableOfApp("chrome", "Google Chrome"))
        assertTrue(isExecutableOfApp("CHROME", "Google Chrome"))
        assertFalse(isExecutableOfApp("msedge", "Google Chrome"))
        assertFalse(isExecutableOfApp("chromedriver", "Google Chrome"))
    }

    @Test
    fun `given lsappinfo info output when parsed then the display name is returned`() {
        assertEquals("Google Chrome", parseLsAppInfoName("\"LSDisplayName\"=\"Google Chrome\"\n"))
    }

    @Test
    fun `given empty lsappinfo info output when parsed then there is no name`() {
        assertNull(parseLsAppInfoName(""))
    }

    @Test
    fun `given the visible process list when parsed then underscores become spaces and marks are removed`() {
        val output = "ASN:0x0-0xa80a8-\"Android_Studio\": ASN:0x0-0x17f17f-\"Google_Chrome\": ASN:0x0-0x12d12d-\"\u200EWhatsApp\":\n"
        assertEquals(listOf("Android Studio", "Google Chrome", "WhatsApp"), parseVisibleProcessList(output))
    }

    @Test
    fun `given a Windows executable path when named then the folder and extension are dropped`() {
        assertEquals("chrome", appNameFromExecutablePath("C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe"))
        assertNull(appNameFromExecutablePath(""))
    }
}
