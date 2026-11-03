package com.jagdeep.logger

import io.github.aakira.napier.Napier

object ConsoleLogging {
    var isEnabled: Boolean = false
        private set

    fun setEnabled(isEnabled: Boolean) {
        this.isEnabled = isEnabled
    }

    fun print(message: String, tag: String?, level: Log.Level = Log.Level.INFO) {
        if (!isEnabled) return

        when (level) {
            Log.Level.DEBUG -> Napier.d(tag = tag, message = message)
            Log.Level.INFO -> Napier.i(tag = tag, message = message)
            Log.Level.WARN -> Napier.w(tag = tag, message = message)
            Log.Level.ERROR -> Napier.e(tag = tag, message = message)
        }
    }
}