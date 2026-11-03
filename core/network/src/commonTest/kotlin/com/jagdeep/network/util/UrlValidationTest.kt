package com.jagdeep.network.util

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UrlValidationTest {
    @Test
    fun identifiesHttpAndHttpsInput() {
        assertTrue(" https://example.com/video ".isLikelyHttpUrl())
        assertTrue("HTTP://example.com/video".isLikelyHttpUrl())
        assertFalse("example.com/video".isLikelyHttpUrl())
        assertFalse("ftp://example.com/video".isLikelyHttpUrl())
    }
}
