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
            // Like Chrome, resolves with the receiving frame's reply.
            sendMessage: (tabId, message) => new Promise((resolve) => {
              let answered = false;
              for (const l of listeners) l(message, {}, (reply) => { answered = true; resolve(reply); });
              if (!answered) resolve(undefined);
            }),
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

    /** A stand-in for playback: no media file is needed, and the page reports what was asked of it. */
    private val videoPage = """
        <!doctype html><html><body style="height:5000px;margin:0">
        <video id="deco" loop style="width:300px;height:150px;display:block"></video>
        <video id="main" style="width:300px;height:150px;display:block"></video>
        <script>
          const notifyTest = (what) => fetch('/report?what=' + what);
          const fakeVideo = (element, name, millis) => {
            let paused = true, ended = false;
            Object.defineProperty(element, 'paused', { get: () => paused });
            Object.defineProperty(element, 'ended', { get: () => ended });
            element.play = () => {
              notifyTest(name + '-play');
              paused = false;
              setTimeout(() => { paused = true; ended = true; notifyTest(name + '-ended'); }, millis);
              return Promise.resolve();
            };
          };
          fakeVideo(document.getElementById('deco'), 'deco', 1000);
          fakeVideo(document.getElementById('main'), 'main', 5000);
          setInterval(() => notifyTest('y-' + Math.round(window.scrollY)), 200);
        </script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    /** A picture and a button, as many players show: the video exists only once it's pressed. */
    private val posterPage = """
        <!doctype html><html><body style="height:5000px;margin:0">
        <button class="vjs-pause-control" style="width:50px;height:50px" onclick="notifyTest('pause-pressed')"></button>
        <div class="preview small big-play-button" style="width:300px;height:150px;background:#333" onclick="startPlayer(this)"></div>
        <script>
          const notifyTest = (what) => fetch('/report?what=' + what);
          function startPlayer(button) {
            notifyTest('play-pressed');
            button.style.display = 'none';
            const video = document.createElement('video');
            video.style.cssText = 'width:300px;height:150px;display:block';
            let paused = false, ended = false;
            Object.defineProperty(video, 'paused', { get: () => paused });
            Object.defineProperty(video, 'ended', { get: () => ended });
            video.play = () => Promise.resolve();
            document.body.prepend(video);
            setTimeout(() => { paused = true; ended = true; notifyTest('video-ended'); }, 4000);
          }
        </script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    private val clearBottom = "\"atBottom\":true"

    private val shortPage = """
        <!doctype html><html><body><p>Not much here.</p>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    /** Reports plenty to scroll, but snaps back every time. */
    private val lockedPage = """
        <!doctype html><html><body style="height:5000px;margin:0">
        <script>addEventListener('scroll', () => scrollTo({ top: 0, behavior: 'instant' }));</script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    /** The top frame never changes, while a second frame's content does, as with a course in an iframe. */
    private val iframeContentPage = """
        <!doctype html><html><body><p>The same frame around changing content.</p>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        <script>
          let content = 'first';
          setInterval(() => {
            const tick = { type: 'tick', rank: null, label: null, area: 0, video: 'none', reading: { atBottom: true, content } };
            for (const l of listeners) l(tick, { tab: { id: 1 }, frameId: 7 }, () => {});
          }, 300);
          setTimeout(() => { content = 'second'; }, 4000);
        </script>
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
    fun `given a scroll and click run in Chrome when it reaches a video then it plays it, holds the page, and goes on only after it ends`() =
        videoRun(videoPage) { reports, incoming ->
            waitFor("the video to start") { "main-play" in reports }
            waitFor("the app to hear it's playing") { incoming.any { "\"video\":\"playing\"" in it } }
            Thread.sleep(1_500)
            assertFalse("main-ended" in reports, "Still playing")
            assertFalse(incoming.any { "\"atBottom\":true" in it && "\"video\":\"none\"" in it }, "It must not report a clear bottom during the video")
            val heldAt = reports.last { it.startsWith("y-") }
            Thread.sleep(1_000)
            assertEquals(heldAt, reports.last { it.startsWith("y-") }, "It must not scroll during the video")

            waitFor("the video to end") { "main-ended" in reports }
            waitFor("the bottom after the video") { incoming.any { "\"atBottom\":true" in it && "\"video\":\"none\"" in it } }
            assertEquals(1, reports.count { it == "main-play" }, "It plays a video once")
            assertFalse("deco-play" in reports, "A looping video is decoration")
        }

    @Test
    fun `given a picture with a play button in Chrome when the run reaches it then it presses it once and waits for the video`() =
        videoRun(posterPage) { reports, incoming ->
            waitFor("the play button to be pressed") { "play-pressed" in reports }
            Thread.sleep(1_500)
            assertFalse("video-ended" in reports, "Still playing")
            assertFalse(incoming.any { "\"atBottom\":true" in it && "\"video\":\"none\"" in it }, "It must wait for the video")

            waitFor("the video to end") { "video-ended" in reports }
            waitFor("the bottom after the video") { incoming.any { "\"atBottom\":true" in it && "\"video\":\"none\"" in it } }
            assertEquals(1, reports.count { it == "play-pressed" }, "It presses a play button once")
            assertFalse("pause-pressed" in reports, "A pause button isn't a play button")
        }

    @Test
    fun `given a page with nothing to scroll in Chrome when a run starts then it counts as the bottom`() =
        videoRun(shortPage) { _, incoming ->
            waitFor("the bottom to be reported") { incoming.any { clearBottom in it && "\"video\":\"none\"" in it } }
        }

    @Test
    fun `given a page that won't move in Chrome when a run keeps trying then it counts as the bottom`() =
        videoRun(lockedPage) { _, incoming ->
            waitFor("the page to be read") { incoming.any { "\"type\":\"page\"" in it } }
            assertFalse(incoming.any { clearBottom in it }, "It should try to scroll first")
            waitFor("the bottom to be reported") { incoming.any { clearBottom in it } }
        }

    @Test
    fun `given content changing in another frame in Chrome when read then the page signature changes`() =
        videoRun(iframeContentPage) { _, incoming ->
            fun signatures() = incoming.filter { clearBottom in it }
                .mapNotNull { Regex("\"signature\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet()
            waitFor("a signature") { signatures().isNotEmpty() }
            waitFor("the signature to change with the other frame") { signatures().size >= 2 }
        }

    /** "Back to Course" appears only after a while, as after a video ends; it reports each press. */
    private val lateButtonPage = """
        <!doctype html><html><body><p>Lesson</p>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        <script>
          setTimeout(() => {
            const button = document.createElement('button');
            button.textContent = 'Back to Course';
            button.onclick = () => fetch('/report?what=back-pressed');
            document.body.append(button);
          }, 2500);
        </script>
        </body></html>
    """.trimIndent()

    @Test
    fun `given a button step in Chrome when its button appears late then it's clicked exactly once and reported`() =
        videoRun(
            lateButtonPage,
            command = """{"enabled":true,"scrolling":false,"pixelsPerSecond":60,"words":[],"advance":0,"autoRun":true,"watchVideos":true,"clickText":"Back to Course","clickRequest":1}""",
        ) { reports, incoming ->
            waitFor("the button to be pressed") { "back-pressed" in reports }
            waitFor("the app to hear it") { incoming.any { "\"type\":\"textClicked\"" in it && "\"request\":1" in it } }
            Thread.sleep(2_000)
            assertEquals(1, reports.count { it == "back-pressed" }, "One request is one press")
        }

    /** A course page shaped like the site's: one part finished, the next not started, each with Watch video. */
    private val coursePage = """
        <!doctype html><html><body>
        <div style="height:120px;width:300px;background:#ccc" role="button" class="course-cover-play" onclick="fetch('/report?what=cover-pressed')"><span class="play-icon">Play</span></div>
        <button onclick="fetch('/report?what=assessment-pressed')">Assessment</button>
        <div class="row"><span>1.1</span><span>Part:1</span><span>VIDEO</span><span>Completed</span><button onclick="fetch('/report?what=part1-pressed')">Watch video</button></div>
        <div class="row"><span>1.2</span><span>Part:2</span><span>VIDEO</span><span>Not Started</span><button id="p2" onclick="fetch('/report?what=part2-pressed')">Watch video</button></div>
        <script>setInterval(() => { if (document.getElementById('p2').style.outlineStyle === 'solid') fetch('/report?what=part2-highlighted'); }, 300);</script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    /** A lesson: a real video element (played by stand-in), then the site's end panel. */
    private val lessonPage = """
        <!doctype html><html><body>
        <video id="v" style="width:300px;height:150px;display:block"></video>
        <div id="panel"></div>
        <script>
          const notifyTest = (what) => fetch('/report?what=' + what);
          const v = document.getElementById('v');
          let paused = true, ended = false;
          Object.defineProperty(v, 'paused', { get: () => paused });
          Object.defineProperty(v, 'ended', { get: () => ended });
          v.play = () => {
            notifyTest('video-play');
            paused = false;
            setTimeout(() => {
              paused = true; ended = true;
              document.getElementById('panel').innerHTML =
                '<button id="quiz" onclick="notifyTest(\'quiz-pressed\')">Skip Quiz</button><button id="back" onclick="notifyTest(\'back-pressed\')">Back to Course</button>';
            }, 2500);
            return Promise.resolve();
          };
          setInterval(() => {
            const back = document.getElementById('back'), quiz = document.getElementById('quiz');
            if (back && back.style.outlineStyle === 'solid') notifyTest('back-highlighted');
            if (quiz && quiz.style.outlineStyle === 'solid') notifyTest('quiz-highlighted');
          }, 300);
        </script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    /** The dashboard's grid: a finished course, then one not finished, each with a cover and a round play button. */
    private val dashboardPage = """
        <!doctype html><html><body>
        <div class="card">
          <div class="cover" onclick="fetch('/report?what=cover1-pressed')" style="height:60px;width:200px;background:#ccc"></div>
          <h3>Emotional Wellness</h3><span>Completed</span><span>75%</span><span>Min. Pass %</span><span>96min</span><span>Duration</span>
          <button onclick="fetch('/report?what=course1-pressed')"><svg width="20" height="20"><circle cx="10" cy="10" r="8"/></svg></button>
        </div>
        <div class="card">
          <div class="cover" onclick="fetch('/report?what=cover2-pressed')" style="height:60px;width:200px;background:#ccc"></div>
          <h3>Economic Wellness</h3><span>75%</span><span>Min. Pass %</span><span>71min</span><span>Duration</span>
          <button id="play2" onclick="fetch('/report?what=course2-pressed')"><svg width="20" height="20"><circle cx="10" cy="10" r="8"/></svg></button>
        </div>
        <script>setInterval(() => { if (document.getElementById('play2').style.outlineStyle === 'solid') fetch('/report?what=course2-highlighted'); }, 300);</script>
        <script src="/stub.js"></script><script src="/background.js"></script><script src="/content.js"></script>
        </body></html>
    """.trimIndent()

    @Test
    fun `given the course helper on the dashboard in Chrome then the next unfinished course's play button is highlighted and nothing is clicked`() =
        videoRun(dashboardPage, command = courseCommand, path = "/dashboard") { reports, incoming ->
            waitFor("the next course to be highlighted") { "course2-highlighted" in reports }
            waitFor("the app to hear its name") { incoming.any { "\"label\":\"Open Economic Wellness\"" in it } }
            Thread.sleep(1_500)
            listOf("course1-pressed", "course2-pressed", "cover1-pressed", "cover2-pressed").forEach {
                assertFalse(it in reports, "Nothing is clicked without a key press: $it")
            }
        }

    private val courseCommand = """{"enabled":true,"scrolling":false,"pixelsPerSecond":60,"words":[],"advance":0,"course":true}"""

    @Test
    fun `given the course helper on a course page in Chrome then the next unfinished part is highlighted and nothing is clicked`() =
        videoRun(coursePage, command = courseCommand, path = "/dashboard/courses/abc") { reports, incoming ->
            waitFor("the next part to be highlighted") { "part2-highlighted" in reports }
            waitFor("the app to hear it") { incoming.any { "\"label\":\"1.2 Part:2\"" in it } }
            Thread.sleep(1_500)
            listOf("part1-pressed", "part2-pressed", "cover-pressed", "assessment-pressed").forEach {
                assertFalse(it in reports, "Nothing is clicked without a key press: $it")
            }
        }

    @Test
    fun `given the course helper on a lesson in Chrome then its video starts, and Back to Course, never the quiz, is highlighted after`() =
        videoRun(lessonPage, command = courseCommand, path = "/dashboard/courses/abc/def") { reports, _ ->
            waitFor("the video to start by itself") { "video-play" in reports }
            waitFor("Back to Course to be highlighted") { "back-highlighted" in reports }
            Thread.sleep(1_000)
            assertFalse("quiz-highlighted" in reports, "A quiz is never highlighted")
            assertFalse("back-pressed" in reports || "quiz-pressed" in reports, "Nothing is clicked without a key press")
            assertEquals(1, reports.count { it == "video-play" })
        }

    private fun videoRun(
        page: String,
        path: String = "/",
        command: String = """{"enabled":true,"scrolling":true,"pixelsPerSecond":2000,"words":[],"advance":0,"autoRun":true,"watchVideos":true}""",
        checks: (ConcurrentLinkedQueue<String>, ConcurrentLinkedQueue<String>) -> Unit,
    ) {
        if (chrome == null) return
        val reports = ConcurrentLinkedQueue<String>()
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
        val bridge = createLocalBridge(linkPort, "http://127.0.0.1:${server.address.port}")
        val incoming = ConcurrentLinkedQueue<String>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { bridge.incoming.collect { incoming += it } }
        bridge.publish(command)
        val profile = Files.createTempDirectory("autoclicker-chrome").toFile()
        val process = ProcessBuilder(chrome, "--headless=new", "--no-first-run", "--user-data-dir=${profile.absolutePath}", "http://127.0.0.1:${server.address.port}$path")
            .redirectErrorStream(true).redirectOutput(File(profile, "chrome.log")).start()
        try {
            checks(reports, incoming)
        } finally {
            process.destroyForcibly().waitFor()
            scope.cancel()
            bridge.close()
            server.stop(0)
            profile.deleteRecursively()
        }
    }

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
