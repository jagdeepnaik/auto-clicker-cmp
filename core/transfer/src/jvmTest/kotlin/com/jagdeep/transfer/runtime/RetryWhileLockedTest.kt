package com.jagdeep.transfer.runtime

import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetryWhileLockedTest {
    @Test
    fun `given a file briefly locked by another process when moved then the move is retried until it succeeds`() {
        var attempts = 0
        val result = retryWhileLocked(initialDelayMillis = 1) {
            attempts++
            if (attempts < 3) throw AccessDeniedException("partial") else "moved"
        }
        assertEquals("moved", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `given a real conflict when moved then it fails at once instead of retrying`() {
        var attempts = 0
        assertFailsWith<FileAlreadyExistsException> {
            retryWhileLocked(initialDelayMillis = 1) { attempts++; throw FileAlreadyExistsException("target") }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `given a lock that never clears when moved then it gives up after the last attempt`() {
        var attempts = 0
        assertFailsWith<AccessDeniedException> {
            retryWhileLocked(attempts = 4, initialDelayMillis = 1) { attempts++; throw AccessDeniedException("partial") }
        }
        assertEquals(4, attempts)
    }
}
