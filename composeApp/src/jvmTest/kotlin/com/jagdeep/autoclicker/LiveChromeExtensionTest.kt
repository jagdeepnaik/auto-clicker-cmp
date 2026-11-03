package com.jagdeep.autoclicker

import com.jagdeep.localbridge.createLocalBridge
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Runs the extension's real scripts and the real local link in a real headless Chrome, using a
 * throwaway profile. Branded Chrome won't load an extension from the command line, so only the
 * `chrome.*` APIs are stand-ins here; loading through chrome://extensions needs a person.
 *
 *   AUTOCLICKER_LIVE_CHROME_TEST="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
 *     ./gradlew :composeApp:jvmTest --tests '*LiveChromeExtensionTest*' --rerun
 */
class LiveChromeExtensionTest {
    private val chrome = System.getenv("AUTOCLICKER_LIVE_CHROME_TEST")

    private val stub = """
        const listeners = [];
        window.chrome = {
          runtime: {
            id: 'test',
            getPlatformInfo: () => {},
            onMessage: { addListener: (l) => listeners.push(l) },
            onStartup: { addListener() {} },
            onInstalled: { addListener() {} },
            sendMessage: (message) => new Promise((resolve) => {
              let waiting = false;
              for (const l of listeners) waiting = l(message, { tab: { id: 1 }, frameId: 0 }, resolve) === true || waiting;
              if (!waiting) resolve();
            }),
          },
          tabs: {
            query: async () => [{ id: 1 }],
            sendMessage: async (tabId, message) => { for (const l of listeners) l(message, {}, () => {}); },
          },
          windows: { WINDOW_ID_NONE: -1, getLastFocused: async () => ({ focused: true }), onFocusChanged: { addListener() {} } },
          alarms: { create() {}, onAlarm: { addListener() {} } },
        };
    """.trimIndent()

    private val page = """
        <!doctype html><html><body style="height:5000px">
        <button id="off" disabled>Next</button>
        <button id="other" onclick="notifyTest('other')">Other</button>
        <a id="next" onclick="notifyTest('next')">NEXT&gt;</a>
        <video id="v"></video>
        <script>
          const notifyTest = (what) => fetch('/report?what=' + what);
          setInterval(() => {
            notifyTest(window.scrollY > 0 ? 'scrolled' : 'top');
            if (document.getElementById('next').style.outlineStyle === 'solid') notifyTest('highlighted');
            if (document.getElementById('off').style.outlineStyle === 'solid') notifyTest('disabled-highlighted');
          }, 300);
          setTimeout(() => document.getElementById('v').dispatchEvent(new Event('ended')), 3000);
        </script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(100)
        }
    }

    private fun HttpExchange.send(type: String, text: String) {
        val body = text.encodeToByteArray()
        responseHeaders.add("Content-Type", type)
        sendResponseHeaders(200, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    private fun bundled(name: String) = javaClass.getResourceAsStream("/chrome-extension/$name")!!.bufferedReader().readText()

    @Test
    fun `given the real scripts in Chrome when keys are reported then it highlights, clicks once per press, scrolls and hears videos`() {
        if (chrome == null) return
        val reports = ConcurrentLinkedQueue<String>()
        // Its own port, so a copy of the app that's already open can't get in the way.
        val linkPort = ServerSocket(0).use { it.localPort }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/report") { exchange ->
                reports += exchange.requestURI.query.substringAfter('=')
                exchange.sendResponseHeaders(204, -1)
                exchange.close()
            }
            createContext("/stub.js") { it.send("text/javascript", stub) }
            createContext("/background.js") { it.send("text/javascript", bundled("background.js").replace("$BROWSER_LINK_PORT", "$linkPort")) }
            createContext("/content.js") { it.send("text/javascript", bundled("content.js")) }
            createContext("/") { it.send("text/html", page) }
            start()
        }
        val origin = "http://127.0.0.1:${server.address.port}"
        val bridge = createLocalBridge(linkPort, origin)
        val incoming = ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { bridge.incoming.collect { incoming += it } }
        fun command(scrolling: Boolean, advance: Int) =
            bridge.publish("""{"enabled":true,"scrolling":$scrolling,"pixelsPerSecond":200,"words":["Next","Play"],"advance":$advance}""")
        command(scrolling = false, advance = 0)

        val profile = Files.createTempDirectory("autoclicker-chrome").toFile()
        val process = ProcessBuilder(chrome, "--headless=new", "--no-first-run", "--user-data-dir=${profile.absolutePath}", "$origin/")
            .redirectErrorStream(true).redirectOutput(File(profile, "chrome.log")).start()
        try {
            waitFor("the link to connect") { bridge.connected.value }
            waitFor("the button to be found") { incoming.any { "\"label\":\"NEXT>\"" in it } }
            waitFor("the highlight") { "highlighted" in reports }
            Thread.sleep(2_000)
            assertFalse("next" in reports, "It clicked without a key press")
            assertFalse("disabled-highlighted" in reports, "It chose a disabled button")

            command(scrolling = false, advance = 1)
            waitFor("the click") { "next" in reports }
            Thread.sleep(1_500)
            assertEquals(1, reports.count { it == "next" }, "One press must be one click")
            assertFalse("other" in reports)
            assertFalse("scrolled" in reports, "It scrolled before being asked")

            waitFor("the video alert") { incoming.any { "videoEnded" in it } }

            reports.clear()
            command(scrolling = true, advance = 1)
            waitFor("the page to scroll") { "scrolled" in reports }
            assertTrue("next" !in reports)

            // A scroll and click run: fast enough to reach the bottom of the 5000px page.
            incoming.clear()
            bridge.publish("""{"enabled":true,"scrolling":true,"pixelsPerSecond":2000,"words":["Next"],"advance":1,"autoRun":true}""")
            waitFor("the bottom to be reported") { incoming.any { "\"type\":\"page\"" in it && "\"atBottom\":true" in it && "\"focused\":true" in it } }
            assertFalse(incoming.any { "127.0.0.1" in it }, "The address must not reach the app")
            assertTrue("next" !in reports, "Reaching the bottom must not make the extension click")
        } finally {
            process.destroyForcibly().waitFor()
            scope.cancel()
            bridge.close()
            server.stop(0)
            profile.deleteRecursively()
        }
    }
}
