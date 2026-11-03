package com.jagdeep.transfer.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayReasonTest {
    @Test
    fun `given a raw Windows error with paths when shown then no path remains`() {
        val raw = "C:\\Users\\jagdeep\\Downloads\\Zynk\\.zynk-partials\\video.mp4.part -> C:\\Users\\jagdeep\\Downloads\\Zynk\\video.mp4: Access is denied"
        val shown = TransferApprovalMessages.forDisplay(raw)
        assertFalse("Users" in shown || "\\" in shown || ".zynk-partials" in shown, shown)
        assertTrue("Access is denied" in shown, shown)
    }

    @Test
    fun `given a raw macOS error with a path when shown then the path is removed`() {
        val shown = TransferApprovalMessages.forDisplay("/Users/jagdeep/Downloads/Zynk/a.txt: No space left on device")
        assertFalse("/Users" in shown, shown)
    }

    @Test
    fun `given a friendly reason when shown then it is unchanged`() {
        val friendly = "The other device is out of storage."
        assertEquals(friendly, TransferApprovalMessages.forDisplay(friendly))
    }

    @Test
    fun `given no reason or a huge one when shown then it is a short sentence`() {
        assertEquals("The other device couldn't finish the transfer. Try again.", TransferApprovalMessages.forDisplay(null))
        assertTrue(TransferApprovalMessages.forDisplay("x ".repeat(500)).length <= 160)
    }
}
