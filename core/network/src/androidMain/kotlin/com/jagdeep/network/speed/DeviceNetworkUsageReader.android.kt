package com.jagdeep.network.speed

import android.net.TrafficStats
import com.jagdeep.common.time.AppClock

actual class DeviceNetworkUsageReader {
    actual fun readSnapshot(): DeviceNetworkUsageSnapshot? {
        val receivedBytes = TrafficStats.getTotalRxBytes()
        val sentBytes = TrafficStats.getTotalTxBytes()
        if (receivedBytes == TrafficStats.UNSUPPORTED.toLong() ||
            sentBytes == TrafficStats.UNSUPPORTED.toLong()
        ) {
            return null
        }
        return DeviceNetworkUsageSnapshot(
            receivedBytes = receivedBytes,
            sentBytes = sentBytes,
            timestampMillis = AppClock.nowMillis(),
        )
    }
}
