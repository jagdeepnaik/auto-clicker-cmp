package com.jagdeep.automation

/**
 * Turns sampled "which keys are down" into single presses. The first sample only sets the
 * baseline, so a key already held when watching starts doesn't count, and a held key fires once.
 */
class HotKeyEdgeDetector {
    private var previous: Set<HotKey>? = null

    /** Returns the keys that went down since the last sample. */
    fun update(down: Set<HotKey>): Set<HotKey> {
        val pressed = previous?.let { down - it }.orEmpty()
        previous = down
        return pressed
    }
}
