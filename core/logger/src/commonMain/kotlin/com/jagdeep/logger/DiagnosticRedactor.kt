package com.jagdeep.logger

/** Keeps diagnostic logs useful without exposing credentials, full links, or local user paths. */
internal object DiagnosticRedactor {
    fun redact(value: String): String = value
        .replace(AuthorizationHeader, "$1[REDACTED]")
        .replace(CookieHeader, "$1[REDACTED]")
        .replace(SensitiveQueryParameter, "$1[REDACTED]")
        .replace(HttpUrl, "$1$2/[REDACTED]")
        .replace(UserPath, "[LOCAL_PATH]")
        .replace(WindowsUserPath, "[LOCAL_PATH]")
        .replace(IpAddress, "[IP_ADDRESS]")

    private val AuthorizationHeader = Regex("(?im)^(\\s*authorization\\s*[:=]\\s*)(.+)$")
    private val CookieHeader = Regex("(?im)^(\\s*(?:cookie|set-cookie)\\s*[:=]\\s*)(.+)$")
    private val SensitiveQueryParameter = Regex(
        "(?i)([?&](?:access_token|api[_-]?key|authorization|cookie|password|token)=)[^&#\\s]+",
    )
    private val HttpUrl = Regex("(?i)(https?://)([^/\\s?#]+)[^\\s]*")
    private val UserPath = Regex("(?i)(?:/Users|/home|/private|/var|/data|/storage)/[^\\s]+")
    private val WindowsUserPath = Regex("(?i)[a-z]:\\\\Users\\\\[^\\s]+")
    private val IpAddress = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
}
