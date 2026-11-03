package com.jagdeep.ui.util

internal object MessageText {
    private val urlRegex = Regex("""\bhttps?://\S+""")
    private val bracketedUrlRegex = Regex("""\s*\[[^\]]*https?://[^\]]*]\s*""")

    fun clean(message: String): String =
        message
            .replace(bracketedUrlRegex, " ")
            .replace(urlRegex, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(':', '-', ',', ';')
            .ifBlank { "Something went wrong." }
}