package com.jagdeep.logger

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticReportBuilderTest {
    @Test
    fun redactsCredentialsFromHeadersAndUrls() {
        val report = DiagnosticReportBuilder.redactAndLimit(
            value = "Authorization: Bearer private-token\\nCookie: session=private\\nhttps://example.com?a=1&token=secret",
            maximumCharacters = 1_000,
        )

        assertFalse(report.contains("private-token"))
        assertFalse(report.contains("session=private"))
        assertFalse(report.contains("token=secret"))
        assertTrue(report.contains("[REDACTED]"))
    }

    @Test
    fun redactsFullUrlsAndLocalPaths() {
        val report = DiagnosticReportBuilder.redactAndLimit(
            value = "https://example.com/private?x=1 /Users/name/Downloads/video.mp4 192.168.1.10",
            maximumCharacters = 1_000,
        )

        assertFalse(report.contains("/private?x=1"))
        assertFalse(report.contains("/Users/name"))
        assertFalse(report.contains("192.168.1.10"))
    }

    @Test
    fun keepsTheMostRecentContentWhenCappingAReport() {
        val report = DiagnosticReportBuilder.redactAndLimit(
            value = "older-content-newer-content",
            maximumCharacters = 14,
        )

        assertTrue(report.startsWith("[Older diagnostic content omitted]"))
        assertTrue(report.endsWith("newer-content"))
    }
}
