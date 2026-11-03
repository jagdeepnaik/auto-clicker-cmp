package com.jagdeep.localbridge

actual fun createLocalBridge(port: Int, allowedOrigin: String): LocalBridge = UnsupportedLocalBridge()
