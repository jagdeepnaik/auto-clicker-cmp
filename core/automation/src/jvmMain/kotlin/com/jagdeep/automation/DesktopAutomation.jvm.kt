package com.jagdeep.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

actual fun createDesktopAutomation(): DesktopAutomation {
    val osName = System.getProperty("os.name").orEmpty()
    return when {
        osName.contains("Mac", ignoreCase = true) -> JvmDesktopAutomation(MacNativeInput())
        osName.contains("Windows", ignoreCase = true) -> JvmDesktopAutomation(WindowsNativeInput())
        else -> UnsupportedDesktopAutomation
    }
}

/** The parts that differ by operating system; everything else goes through AWT. */
internal interface NativeInput : InputAccessGate, ForegroundApps {
    fun isKeyDown(key: HotKey): Boolean

    fun setDisplayAwake(awake: Boolean)
}

private class JvmDesktopAutomation(private val native: NativeInput) : DesktopAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val pointer: PointerDevice = AwtPointerDevice()
    override val access: InputAccessGate = native
    override val hotKeys: HotKeyMonitor = PollingHotKeyMonitor(native::isKeyDown, scope)
    override val apps: ForegroundApps = CachedForegroundApps(native)

    override fun playAlert() {
        runCatching { java.awt.Toolkit.getDefaultToolkit().beep() }
    }

    override fun setDisplayAwake(awake: Boolean) = native.setDisplayAwake(awake)

    override fun close() {
        native.setDisplayAwake(false)
        scope.cancel()
    }
}
