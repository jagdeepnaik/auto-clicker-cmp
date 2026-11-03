package com.jagdeep.transfer.runtime

import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

/**
 * A receiver's reason for not saving a file, in words the sender can show. Raw system messages
 * are never sent: they carry this device's file paths and read like an error dump.
 */
internal fun Throwable.toReceiverSaveMessage(): String = when {
    this is AccessDeniedException -> "The other device couldn't save the file: it was in use or access was denied. Try again."
    this is FileAlreadyExistsException -> "A file with this name already exists on the other device."
    this is NoSuchFileException -> "The other device's receive folder is no longer available."
    message.orEmpty().contains("space", ignoreCase = true) -> "The other device is out of storage."
    else -> "The other device couldn't save the file. Try again."
}
