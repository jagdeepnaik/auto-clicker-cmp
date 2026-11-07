package com.jagdeep.autoclicker

import com.jagdeep.logger.Log
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * Gives a Mac window the taller title bar Android Studio has, with the red, yellow and green buttons
 * centred in it. Java can't move those buttons, so this asks AppKit directly: an empty, compact
 * NSToolbar makes macOS itself draw a taller bar and centre the buttons. All AppKit work runs on the
 * main thread, and each call is checked first, so on a macOS that lacks one nothing changes.
 */
internal object MacWindowToolbar {
    /** NSWindowToolbarStyleUnifiedCompact: the slimmer of the two unified toolbar heights. */
    private const val TOOLBAR_STYLE_UNIFIED_COMPACT = 4L

    /** NSTitlebarSeparatorStyleNone: the app draws its own frame, so no system line under the bar. */
    private const val SEPARATOR_STYLE_NONE = 1L

    /** objc_msgSend must be called with each exact argument list on Apple silicon, so one per shape. */
    @Suppress("FunctionName")
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Pointer?): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Long): Pointer?

        companion object {
            val INSTANCE: ObjC = Native.load("objc", ObjC::class.java)
        }
    }

    /** The same function, for calls that answer with a number or a BOOL. */
    @Suppress("FunctionName")
    private interface ObjCNumbers : Library {
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Long
        fun objc_msgSend(receiver: Pointer, selector: Pointer, argument: Pointer?): Byte

        companion object {
            val INSTANCE: ObjCNumbers = Native.load("objc", ObjCNumbers::class.java)
        }
    }

    private fun interface MainThreadWork : Callback {
        fun invoke(context: Pointer?)
    }

    @Suppress("FunctionName")
    private interface Dispatch : Library {
        fun dispatch_async_f(queue: Pointer, context: Pointer?, work: MainThreadWork)

        companion object {
            val INSTANCE: Dispatch = Native.load("System", Dispatch::class.java)
        }
    }

    // Kept until it has run, so the callback isn't collected while AppKit still holds it.
    private val pending = mutableSetOf<MainThreadWork>()

    /** Makes the window titled [title] use the taller bar; [onDone] gets true when it did. */
    fun makeTall(title: String, onDone: (Boolean) -> Unit) {
        val work = runCatching {
            val mainQueue = NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q")
            lateinit var task: MainThreadWork
            task = MainThreadWork {
                val done = runCatching { applyOnMainThread(title) }
                    .onFailure { Log.w("Taller title bar not applied: ${it.message}", TAG) }
                    .getOrDefault(false)
                synchronized(pending) { pending.remove(task) }
                onDone(done)
            }
            synchronized(pending) { pending.add(task) }
            Dispatch.INSTANCE.dispatch_async_f(mainQueue, null, task)
        }
        if (work.isFailure) {
            Log.w("Taller title bar not applied: ${work.exceptionOrNull()?.message}", TAG)
            onDone(false)
        }
    }

    private fun applyOnMainThread(title: String): Boolean {
        val objc = ObjC.INSTANCE
        val numbers = ObjCNumbers.INSTANCE
        fun sel(name: String) = objc.sel_registerName(name)
        fun responds(target: Pointer, selector: String) = numbers.objc_msgSend(target, sel("respondsToSelector:"), sel(selector)).toInt() != 0

        val app = objc.objc_msgSend(objc.objc_getClass("NSApplication") ?: return false, sel("sharedApplication")) ?: return false
        val windows = objc.objc_msgSend(app, sel("windows")) ?: return false
        val count = numbers.objc_msgSend(windows, sel("count"))
        val window = (0 until count).asSequence()
            .mapNotNull { objc.objc_msgSend(windows, sel("objectAtIndex:"), it) }
            .firstOrNull { candidate ->
                val name = objc.objc_msgSend(candidate, sel("title")) ?: return@firstOrNull false
                objc.objc_msgSend(name, sel("UTF8String"))?.getString(0) == title
            } ?: return false
        if (!responds(window, "setToolbarStyle:")) return false

        val toolbarClass = objc.objc_getClass("NSToolbar") ?: return false
        val toolbar = objc.objc_msgSend(objc.objc_msgSend(toolbarClass, sel("alloc")) ?: return false, sel("init")) ?: return false
        objc.objc_msgSend(window, sel("setToolbar:"), toolbar)
        objc.objc_msgSend(window, sel("setToolbarStyle:"), TOOLBAR_STYLE_UNIFIED_COMPACT)
        if (responds(window, "setTitlebarSeparatorStyle:")) {
            objc.objc_msgSend(window, sel("setTitlebarSeparatorStyle:"), SEPARATOR_STYLE_NONE)
        }
        // The window holds its own reference now.
        objc.objc_msgSend(toolbar, sel("release"))
        return true
    }

    private const val TAG = "MacWindowToolbar"
}
