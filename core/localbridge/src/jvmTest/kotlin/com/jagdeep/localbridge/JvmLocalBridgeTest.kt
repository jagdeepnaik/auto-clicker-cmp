package com.jagdeep.localbridge

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class JvmLocalBridgeTest {
    private val origin = "chrome-extension://testextension"
    private val bridge = JvmLocalBridge(0, origin, pollHoldMillis = 200)
    // "Origin" is a restricted header in java.net.http unless the test JVM allows it.
    private val client = run {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "origin")
        HttpClient.newHttpClient()
    }

    @AfterTest
    fun tearDown() = bridge.close()

    private fun request(path: String, origin: String? = this.origin) = HttpRequest.newBuilder(URI("http://127.0.0.1:${bridge.boundPort}$path")).apply {
        if (origin != null) header("Origin", origin)
    }

    private fun get(path: String, origin: String? = this.origin) =
        client.send(request(path, origin).GET().build(), HttpResponse.BodyHandlers.ofString())

    private fun post(body: String, origin: String? = this.origin) =
        client.send(request("/event", origin).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `given a web page or no origin when calling then it is refused`() {
        assertEquals(403, get("/state?after=-1", origin = "https://example.com").statusCode())
        assertEquals(403, get("/state?after=-1", origin = null).statusCode())
        assertEquals(403, post("{}", origin = "https://example.com").statusCode())
    }

    private fun getWith(vararg headers: Pair<String, String>) = client.send(
        request("/state?after=-1", origin = null).apply { headers.forEach { (name, value) -> header(name, value) } }.GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).statusCode()

    @Test
    fun `given what Chrome sends for an extension when polling then it is answered`() {
        // Recorded from real Chrome: no Origin, Sec-Fetch-Site none, Sec-Fetch-Mode cors.
        assertEquals(200, getWith("Sec-Fetch-Site" to "none", "Sec-Fetch-Mode" to "cors"))
    }

    @Test
    fun `given what Chrome sends for a web page or a typed address then it is refused`() {
        assertEquals(403, getWith("Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "no-cors"))
        assertEquals(403, getWith("Sec-Fetch-Site" to "cross-site", "Sec-Fetch-Mode" to "cors"))
        assertEquals(403, getWith("Sec-Fetch-Site" to "same-site", "Sec-Fetch-Mode" to "cors"))
        assertEquals(403, getWith("Sec-Fetch-Site" to "none", "Sec-Fetch-Mode" to "navigate"))
    }

    @Test
    fun `given the trusted client when polling then it gets the published state`() {
        bridge.publish("""{"advance":1}""")
        val response = get("/state?after=-1")
        assertEquals(200, response.statusCode())
        assertEquals("""{"version":1,"state":{"advance":1}}""", response.body())
    }

    @Test
    fun `given no change when polling then it answers after the hold with the same version`() {
        bridge.publish("1")
        assertEquals("""{"version":1,"state":1}""", get("/state?after=1").body())
    }

    @Test
    fun `given a waiting poll when state is published then it answers right away`() {
        val slow = JvmLocalBridge(0, origin, pollHoldMillis = 30_000)
        try {
            slow.publish("1")
            val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${slow.boundPort}/state?after=1")).header("Origin", origin).GET().build()
            val pending = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            Thread.sleep(200)
            slow.publish("2")
            assertEquals("""{"version":2,"state":2}""", pending.get().body())
        } finally {
            slow.close()
        }
    }

    @Test
    fun `given a client version from before a restart when polling then it catches up at once`() {
        bridge.publish("1")
        assertEquals("""{"version":1,"state":1}""", get("/state?after=99").body())
    }

    @Test
    fun `given the trusted client when posting then the message arrives and oversized ones are refused`() = runBlocking {
        val received = async(Dispatchers.Default) { withTimeout(5_000) { bridge.incoming.first() } }
        Thread.sleep(200)
        assertEquals(204, post("""{"type":"videoEnded"}""").statusCode())
        assertEquals("""{"type":"videoEnded"}""", received.await())
        assertEquals(413, post("x".repeat(17 * 1024)).statusCode())
    }

    @Test
    fun `given a client checking in when watched then it counts as connected`() = runBlocking {
        get("/state?after=-1")
        withTimeout(5_000) { bridge.connected.first { it } }
        assertTrue(bridge.connected.value)
    }

    @Test
    fun `given the port is taken when starting then it explains instead of failing`() {
        val second = JvmLocalBridge(assertNotNull(bridge.boundPort), origin)
        try {
            assertNotNull(second.problem.value)
            assertEquals(null, second.boundPort)
        } finally {
            second.close()
        }
    }
}
