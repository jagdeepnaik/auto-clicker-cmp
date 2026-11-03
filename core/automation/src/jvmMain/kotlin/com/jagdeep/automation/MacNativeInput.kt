package com.jagdeep.automation

import com.jagdeep.logger.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.util.concurrent.TimeUnit

/** macOS: Accessibility decides whether input reaches other apps; Robot fails silently without it. */
internal class MacNativeInput : NativeInput {
    private var promptShown = false

    override fun access(): InputAccess =
        if (runCatching { ApplicationServices.INSTANCE.AXIsProcessTrusted().toInt() != 0 }.getOrDefault(false)) {
            InputAccess.Granted
        } else {
            InputAccess.NeedsPermission
        }

    /** The first request shows the system prompt, which also adds this app to the Accessibility list. */
    override fun requestAccess(): Boolean {
        if (!promptShown) {
            promptShown = true
            if (runCatching { showAccessibilityPrompt() }.onFailure { Log.w("Prompt failed: ${it.message}", TAG) }.isSuccess) return true
        }
        return run(listOf("/usr/bin/open", ACCESSIBILITY_SETTINGS_URL)) != null
    }

    override fun isKeyDown(key: HotKey): Boolean =
        ApplicationServices.INSTANCE.CGEventSourceKeyState(COMBINED_SESSION_STATE, key.macKeyCode()).toInt() != 0

    override fun frontmost(): String? {
        val front = run(listOf(LSAPPINFO, "front"))?.trim()?.trimEnd(':')?.takeIf { it.isNotEmpty() } ?: return null
        return parseLsAppInfoName(run(listOf(LSAPPINFO, "info", "-only", "name", front)).orEmpty())
    }

    override fun running(): List<String> =
        parseVisibleProcessList(run(listOf(LSAPPINFO, "visibleProcessList")).orEmpty())

    private fun showAccessibilityPrompt() {
        val services = NativeLibrary.getInstance("ApplicationServices")
        val foundation = NativeLibrary.getInstance("CoreFoundation")
        val options = CoreFoundation.INSTANCE.CFDictionaryCreate(
            null,
            arrayOf(services.getGlobalVariableAddress("kAXTrustedCheckOptionPrompt").getPointer(0)),
            arrayOf(foundation.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)),
            1L,
            foundation.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            foundation.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks"),
        )
        try {
            ApplicationServices.INSTANCE.AXIsProcessTrustedWithOptions(options)
        } finally {
            CoreFoundation.INSTANCE.CFRelease(options)
        }
    }

    private fun run(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else {
            output.takeIf { process.exitValue() == 0 }
        }
    }.getOrNull()

    /** C `bool` results are declared as bytes: JNA would otherwise read undefined upper bits. */
    @Suppress("FunctionName")
    private interface ApplicationServices : Library {
        fun AXIsProcessTrusted(): Byte
        fun AXIsProcessTrustedWithOptions(options: Pointer?): Byte
        fun CGEventSourceKeyState(stateId: Int, key: Short): Byte

        companion object {
            val INSTANCE: ApplicationServices = Native.load("ApplicationServices", ApplicationServices::class.java)
        }
    }

    @Suppress("FunctionName")
    private interface CoreFoundation : Library {
        fun CFDictionaryCreate(
            allocator: Pointer?,
            keys: Array<Pointer>,
            values: Array<Pointer>,
            count: Long,
            keyCallbacks: Pointer,
            valueCallbacks: Pointer,
        ): Pointer

        fun CFRelease(value: Pointer)

        companion object {
            val INSTANCE: CoreFoundation = Native.load("CoreFoundation", CoreFoundation::class.java)
        }
    }

    private companion object {
        const val TAG = "MacNativeInput"
        const val LSAPPINFO = "/usr/bin/lsappinfo"
        const val COMMAND_TIMEOUT_SECONDS = 2L

        /** kCGEventSourceStateCombinedSessionState: keys pressed in the user's session, from any source. */
        const val COMBINED_SESSION_STATE = 0
        const val ACCESSIBILITY_SETTINGS_URL = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
    }
}

/** Reads the name from `lsappinfo info -only name`: `"LSDisplayName"="Google Chrome"`. */
internal fun parseLsAppInfoName(output: String): String? =
    Regex("\"LSDisplayName\"=\"(.*)\"").find(output)?.groupValues?.get(1)?.let(::normalizedAppName)?.takeIf { it.isNotEmpty() }

/** Reads `lsappinfo visibleProcessList`: `ASN:0x0-0x17f17f-"Google_Chrome": ...`, where `_` stands for a space. */
internal fun parseVisibleProcessList(output: String): List<String> =
    Regex("\"([^\"]+)\":").findAll(output)
        .map { normalizedAppName(it.groupValues[1].replace('_', ' ')) }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

/** Virtual key codes from Carbon's `Events.h`; they don't follow the function keys' order. */
private fun HotKey.macKeyCode(): Short = when (this) {
    HotKey.F1 -> 122
    HotKey.F2 -> 120
    HotKey.F3 -> 99
    HotKey.F4 -> 118
    HotKey.F5 -> 96
    HotKey.F6 -> 97
    HotKey.F7 -> 98
    HotKey.F8 -> 100
    HotKey.F9 -> 101
    HotKey.F10 -> 109
    HotKey.F11 -> 103
    HotKey.F12 -> 111
}
