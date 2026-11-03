package com.jagdeep.network.speed

data class DeviceNetworkUsageSnapshot(
    val receivedBytes: Long,
    val sentBytes: Long,
    val timestampMillis: Long,
)

data class NetworkSpeed(
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
)

expect class DeviceNetworkUsageReader() {
    fun readSnapshot(): DeviceNetworkUsageSnapshot?
}
