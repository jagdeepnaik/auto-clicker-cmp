package com.jagdeep.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

class CachedForegroundAppsTest {
    private class CountingApps : ForegroundApps {
        var reads = 0
        override fun frontmost(): String = "App ${++reads}"
        override fun running(): List<String> = emptyList()
    }

    @Test
    fun `given repeated asks within the cache age when reading then the source is asked once`() {
        val time = TestTimeSource()
        val source = CountingApps()
        val apps = CachedForegroundApps(source, time)

        assertEquals("App 1", apps.frontmost())
        time += 100.milliseconds
        assertEquals("App 1", apps.frontmost())
        assertEquals(1, source.reads)
    }

    @Test
    fun `given the cache has aged when reading then the source is asked again`() {
        val time = TestTimeSource()
        val apps = CachedForegroundApps(CountingApps(), time)

        apps.frontmost()
        time += 250.milliseconds
        assertEquals("App 2", apps.frontmost())
    }
}
