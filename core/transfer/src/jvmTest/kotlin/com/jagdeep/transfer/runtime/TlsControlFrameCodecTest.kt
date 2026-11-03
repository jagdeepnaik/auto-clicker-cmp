package com.jagdeep.transfer.runtime

import com.jagdeep.transfer.protocol.TransferProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class TlsControlFrameCodecTest {
    @Test
    fun `round trips a bounded control payload`() {
        val output = ByteArrayOutputStream()
        val payload = "control-message".encodeToByteArray()
        TlsControlFrameCodec.write(output, payload)
        assertContentEquals(payload, TlsControlFrameCodec.read(ByteArrayInputStream(output.toByteArray())))
    }

    @Test
    fun `rejects oversized control payload`() {
        assertFailsWith<IllegalArgumentException> {
            TlsControlFrameCodec.write(ByteArrayOutputStream(), ByteArray(TransferProtocol.MAX_CONTROL_MESSAGE_BYTES + 1))
        }
    }
}
