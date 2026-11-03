package com.jagdeep.automation

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** A fast click loop asks constantly; finding the front app is too slow to do every time. */
internal class CachedForegroundApps(
    private val source: ForegroundApps,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ForegroundApps {
    private var cached: String? = null
    private var readAt: TimeMark? = null

    @Synchronized
    override fun frontmost(): String? {
        val mark = readAt
        if (mark == null || mark.elapsedNow() >= MAX_AGE) {
            cached = source.frontmost()
            readAt = timeSource.markNow()
        }
        return cached
    }

    override fun running(): List<String> = source.running()

    private companion object {
        val MAX_AGE = 250.milliseconds
    }
}
