package com.jagdeep.common.time

import kotlin.time.Clock
import kotlin.time.Instant

object AppClock {
    fun nowInstant(): Instant = Clock.System.now()

    fun nowMillis(): Long = nowInstant().toEpochMilliseconds()

    fun nowMillisString(): String = nowMillis().toString()
}
