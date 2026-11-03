package com.jagdeep.logger.har

import com.jagdeep.logger.HarContent
import com.jagdeep.logger.HarLogRecorder
import com.jagdeep.logger.HarEntry
import com.jagdeep.logger.HarNameValue
import com.jagdeep.logger.HarPostData
import com.jagdeep.logger.HarRequest
import com.jagdeep.logger.HarResponse
import com.jagdeep.logger.HarTimings
import com.jagdeep.logger.Log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class HarLogRecorderTest {
    @Test
    fun exportHarCreatesChromeCompatibleRootObject() {
        Log.setHarLoggingEnabled(false)

        val content = HarLogRecorder.exportHar()
        val root = Json.parseToJsonElement(content).jsonObject
        val log = root.getValue("log").jsonObject

        assertTrue(content.trimStart().startsWith("{"))
        assertEquals("1.2", log.getValue("version").jsonPrimitive.content)
        assertIs<JsonObject>(log.getValue("creator"))
        assertIs<JsonArray>(log.getValue("pages"))
        assertIs<JsonArray>(log.getValue("entries"))
    }

    @Test
    fun exportHarIncludesChromeStylePostEntryFields() {
        Log.setHarLoggingEnabled(true)
        HarLogRecorder.record(
            HarEntry(
                startedDateTime = "2026-07-22T00:00:00Z",
                time = 12.0,
                request = HarRequest(
                    method = "POST",
                    url = "http://10.12.64.254:8090/logout.xml",
                    headers = emptyList(),
                    queryString = emptyList(),
                    postData = HarPostData(
                        mimeType = "application/x-www-form-urlencoded",
                        params = listOf(HarNameValue("mode", "193")),
                        text = "mode=193",
                    ),
                ),
                response = HarResponse(
                    status = 200,
                    statusText = "OK",
                    headers = emptyList(),
                    content = HarContent(size = 0, mimeType = "text/xml"),
                ),
                timings = HarTimings(wait = 12.0),
            )
        )

        val entry = Json.parseToJsonElement(HarLogRecorder.exportHar())
            .jsonObject
            .getValue("log").jsonObject
            .getValue("entries") as JsonArray

        val first = entry.first().jsonObject
        assertEquals("xhr", first.getValue("_resourceType").jsonPrimitive.content)
        assertIs<JsonObject>(first.getValue("cache"))
        assertEquals(
            "application/x-www-form-urlencoded",
            first.getValue("request")
                .jsonObject
                .getValue("postData")
                .jsonObject
                .getValue("mimeType")
                .jsonPrimitive
                .content,
        )

        Log.setHarLoggingEnabled(false)
    }
}
