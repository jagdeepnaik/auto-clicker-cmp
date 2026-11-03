package com.jagdeep.transfer.runtime

import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileErrorMessagesTest {
    @Test
    fun `given file-system failures when reported to the sender then the reason is plain and has no path`() {
        val path = "C:\\Users\\me\\Downloads\\Zynk\\video.mp4"
        listOf(
            AccessDeniedException(path) to "in use",
            FileAlreadyExistsException(path) to "already exists",
            IOException("$path: There is not enough space on the disk") to "out of storage",
            IOException("$path: something odd") to "couldn't save",
        ).forEach { (failure, expected) ->
            val message = failure.toReceiverSaveMessage()
            assertFalse("Users" in message, message)
            assertEquals(true, expected in message, message)
        }
    }
}
