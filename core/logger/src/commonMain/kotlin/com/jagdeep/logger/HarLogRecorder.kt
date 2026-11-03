package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig
import com.jagdeep.common.time.AppClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

object HarLogRecorder {
    private const val MAX_ENTRIES = 500
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val entries = mutableListOf<HarEntry>()

    var isEnabled: Boolean = false
        private set

    fun setEnabled(isEnabled: Boolean) {
        this.isEnabled = isEnabled
        if (!isEnabled) {
            clear()
        }
    }

    fun clear() {
        entries.clear()
    }

    fun record(entry: HarEntry) {
        if (!isEnabled) return

        entries += entry
        if (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
        }
    }

    fun exportHar(): String {
        val content = json.encodeToString(
            HarFile.serializer(),
            HarFile(
                log = HarLog(
                    pages = listOf(HarPage()),
                    entries = entries.toList(),
                )
            )
        )
        val root = json.parseToJsonElement(content)
        require(root is JsonObject && root.jsonObject["log"] is JsonObject) {
            "Generated HAR content must be a JSON object with a log object."
        }
        return content
    }

    fun exportHarFile(): String {
        val fileName =
            "${AppConfig.APP_NAME_LOWERCASE}-${AppClock.nowMillis()}.har"
        return writeHarFile(fileName = fileName, content = exportHar())
    }
}
