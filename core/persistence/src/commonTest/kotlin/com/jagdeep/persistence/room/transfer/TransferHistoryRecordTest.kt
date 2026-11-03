package com.jagdeep.persistence.room.transfer

import kotlin.test.Test
import kotlin.test.assertFailsWith

class TransferHistoryRecordTest {
    @Test
    fun `given a sensitive-length error when creating history then it is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            TransferHistoryRecord("id", TransferDirection.SENT, "peer", "file", 1, TransferHistoryStatus.FAILED, 1, errorMessage = "x".repeat(513))
        }
    }
}
