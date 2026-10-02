package io.github.junwen853.cloudtransfer.icloud

import java.io.IOException

/** Apple no longer accepts the saved web session: the user must sign in again. */
class SessionExpiredException(message: String = SESSION_EXPIRED_MESSAGE) : IOException(message)

/** iCloud refuses web access to the library (ADP or "Access iCloud Data on the Web" off). */
class WebAccessDeniedException(message: String = WEB_ACCESS_DENIED_GUIDANCE) : IOException(message)

/** A dropped connection, a timeout or a server-side error; trying again may work. */
class TransientAppleException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Apple answered, but not in the expected shape. */
class AppleProtocolException(message: String) : IOException(message)

const val SESSION_EXPIRED_MESSAGE = "iCloud 登录已失效，请在 App 里重新登录 iCloud。"

const val WEB_ACCESS_DENIED_GUIDANCE =
    "iCloud 拒绝了网页数据访问（ACCESS_DENIED），这不是登录过期。请在受信任的 iPhone、iPad 或 Mac 上打开" +
        "“设置 > Apple 账户 > iCloud > 在网页上访问 iCloud 数据”；如果开启了高级数据保护，还要在受信任设备上批准这次网页访问。" +
        "完成后回到本 App 重新登录。"

object AppleErrors {
    private val AUTH_STATUS = setOf(401, 403, 421, 450)
    private val AUTH_MARKERS = listOf(
        "AUTHENTICATION_FAILED", "NOT_AUTHENTICATED", "AUTHENTICATION_REQUIRED",
        "MISSING_PCS", "INVALID_PCS", "MissingPCS", "InvalidPCS", "Missing X-APPLE-WEBAUTH-TOKEN",
    )
    private val ACCESS_DENIED_MARKERS = listOf("ACCESS_DENIED", "WEB_ACCESS_DISABLED")

    fun isAuthStatus(status: Int): Boolean = status in AUTH_STATUS || status == 423 || status == 424

    fun hasAuthMarker(text: String): Boolean = AUTH_MARKERS.any { text.contains(it) }

    fun hasAccessDeniedMarker(text: String): Boolean = ACCESS_DENIED_MARKERS.any { text.contains(it) }

    fun isRetryableStatus(status: Int): Boolean = status <= 0 || status >= 500 || status == 408 || status == 425 || status == 429

    /**
     * Throws the matching exception for a failed HTTP answer of an Apple
     * service. [step] names the request in Chinese for the message.
     */
    fun throwForHttpFailure(step: String, status: Int, body: String): Nothing {
        if (hasAccessDeniedMarker(body)) throw WebAccessDeniedException()
        if (isAuthStatus(status) || hasAuthMarker(body)) throw SessionExpiredException()
        if (isRetryableStatus(status)) throw TransientAppleException("$step：Apple 暂时出错（HTTP $status）：${compact(body)}")
        throw AppleProtocolException("$step HTTP $status：${compact(body)}")
    }

    /** Error text from a JSON body that reports a service error, or "". */
    fun serviceError(json: org.json.JSONObject): String {
        val errors = json.opt("errors")
        if (errors is org.json.JSONArray && errors.length() > 0) return errors.toString()
        if (errors is org.json.JSONObject && errors.length() > 0) return errors.toString()
        val code = firstNonBlank(
            json.text("errorCode"), json.text("serverErrorCode"), json.text("extensionErrorCode"),
        )
        val reason = firstNonBlank(json.text("errorMessage"), json.text("reason"), json.text("errorReason"))
        val failed = json.optBoolean("hasError", false) || (json.has("success") && !json.optBoolean("success", true)) ||
            code.isNotEmpty() || hasAccessDeniedMarker(reason) || hasAuthMarker(reason)
        if (!failed) return ""
        val detail = reason.ifEmpty { "Apple 未提供错误说明" }
        return if (code.isNotEmpty()) "$code：$detail" else detail
    }

    /** Raises the matching exception for an error text reported inside a 2xx answer. */
    fun throwForServiceError(step: String, error: String): Nothing {
        if (hasAccessDeniedMarker(error)) throw WebAccessDeniedException()
        if (hasAuthMarker(error)) throw SessionExpiredException()
        if (error.contains("TRY_AGAIN_LATER") || error.contains("THROTTLED") || error.contains("SERVER_ERROR") ||
            error.contains("INTERNAL_ERROR")
        ) {
            throw TransientAppleException("$step：Apple 暂时出错：${compact(error)}")
        }
        throw AppleProtocolException("$step 返回错误：${compact(error)}")
    }

    fun compact(body: String): String {
        val normalized = body.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= 260) normalized else normalized.substring(0, 260) + "..."
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && it != "null" }?.trim().orEmpty()
}
