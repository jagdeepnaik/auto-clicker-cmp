package com.jagdeep.common

import com.jagdeep.common.platform.Platform

expect fun getPlatform(): Platform

expect fun isAndroid(): Boolean

expect fun isIos(): Boolean

expect fun isJvm(): Boolean

expect fun isWeb(): Boolean