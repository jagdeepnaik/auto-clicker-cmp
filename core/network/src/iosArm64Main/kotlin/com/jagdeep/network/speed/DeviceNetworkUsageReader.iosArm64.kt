package com.jagdeep.network.speed

import com.jagdeep.common.time.AppClock
import com.jagdeep.network.speed.cinterop.cn_network_received_bytes
import com.jagdeep.network.speed.cinterop.cn_network_sent_bytes
import kotlinx.cinterop.ExperimentalForeignApi

actual class DeviceNetworkUsageReader {
    @OptIn(ExperimentalForeignApi::class)
    actual fun readSnapshot(): DeviceNetworkUsageSnapshot? {
        val receivedBytes = cn_network_received_bytes()
        val sentBytes = cn_network_sent_bytes()
        if (receivedBytes < 0L || sentBytes < 0L) {
            return null
        }
        return DeviceNetworkUsageSnapshot(
            receivedBytes = receivedBytes,
            sentBytes = sentBytes,
            timestampMillis = AppClock.nowMillis(),
        )
    }
}
