package com.jagdeep.logger

import com.jagdeep.common.config.AppConfig
import com.jagdeep.common.time.AppClock
import kotlinx.serialization.Serializable

@Serializable
data class HarFile(
    val log: HarLog,
)

@Serializable
data class HarLog(
    val version: String = "1.2",
    val creator: HarCreator = HarCreator(),
    val pages: List<HarPage>,
    val entries: List<HarEntry>,
)

@Serializable
data class HarCreator(
    val name: String = AppConfig.APP_NAME_LOWERCASE,
    val version: String = AppConfig.APP_VERSION_NAME,
)

@Serializable
data class HarEntry(
    val startedDateTime: String,
    val time: Double,
    val request: HarRequest,
    val response: HarResponse,
    val cache: HarCache = HarCache(),
    val timings: HarTimings,
    val pageref: String? = null,
    val connection: String = "",
    val serverIPAddress: String = "",
    val _resourceType: String = "xhr",
    val _priority: String = "High",
    val comment: String? = null,
)

private const val HarPageId = "zynk-page"

@Serializable
data class HarPage(
    val startedDateTime: String = AppClock.nowInstant().toString(),
    val id: String = HarPageId,
    val title: String = "Zynk Network Debug Session",
    val pageTimings: HarPageTimings = HarPageTimings(),
)

@Serializable
data class HarPageTimings(
    val onContentLoad: Double = -1.0,
    val onLoad: Double = -1.0,
)

@Serializable
data class HarRequest(
    val method: String,
    val url: String,
    val httpVersion: String = "HTTP/1.1",
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val queryString: List<HarNameValue>,
    val postData: HarPostData? = null,
    val headersSize: Int = -1,
    val bodySize: Int = -1,
)

@Serializable
data class HarPostData(
    val mimeType: String,
    val params: List<HarNameValue> = emptyList(),
    val text: String = "",
)

@Serializable
data class HarResponse(
    val status: Int,
    val statusText: String,
    val httpVersion: String = "HTTP/1.1",
    val cookies: List<HarNameValue> = emptyList(),
    val headers: List<HarNameValue>,
    val content: HarContent,
    val redirectURL: String = "",
    val headersSize: Int = -1,
    val bodySize: Int = -1,
)

@Serializable
data class HarContent(
    val size: Long,
    val mimeType: String = "",
    val text: String = "",
)

@Serializable
data class HarTimings(
    val blocked: Double = -1.0,
    val dns: Double = -1.0,
    val connect: Double = -1.0,
    val send: Double = 0.0,
    val wait: Double,
    val receive: Double = 0.0,
    val ssl: Double = -1.0,
)

@Serializable
data class HarNameValue(
    val name: String,
    val value: String,
)

@Serializable
class HarCache
