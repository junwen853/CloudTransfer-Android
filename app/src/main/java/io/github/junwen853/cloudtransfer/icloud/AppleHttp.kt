package io.github.junwen853.cloudtransfer.icloud

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Where the HTTP layer reads the current session and stores rotated cookies. */
interface SessionAccess {
    fun current(): ICloudSession

    /**
     * Applies Set-Cookie headers of an Apple response (Apple rotates session
     * tokens). Must be atomic: parallel requests rotate cookies concurrently.
     */
    fun applySetCookies(setCookieHeaders: List<String>)
}

class AppleResponse(val code: Int, val body: String)

/**
 * HTTP client for Apple's iCloud web services. Every request carries the
 * headers of the iCloud webpage (desktop browser, iCloud origin) and the saved
 * session cookie; cookies Apple rotates in responses are written back.
 */
class AppleHttp(private val session: SessionAccess) {

    suspend fun postJson(url: String, json: String, readTimeoutSeconds: Long = 45, step: String = "iCloud 请求"): AppleResponse {
        val request = baseRequest(url, readTimeoutSeconds)
            .post(json.toRequestBody(TEXT_PLAIN_UTF8))
            .header("Content-Type", "text/plain;charset=UTF-8")
            .build()
        return execute(request, readTimeoutSeconds, step)
    }

    suspend fun get(url: String, readTimeoutSeconds: Long = 45, step: String = "iCloud 请求"): AppleResponse =
        execute(baseRequest(url, readTimeoutSeconds).get().build(), readTimeoutSeconds, step)

    /** Downloads binary content (a thumbnail) from an Apple content URL. */
    suspend fun download(url: String, maxBytes: Int, step: String = "下载缩略图"): ByteArray {
        val host = hostOf(url)
        require(ICloudEndpoints.isTrustedSignedUploadHost(host)) { "缩略图地址不在 Apple 域名内。" }
        val current = session.current()
        val builder = Request.Builder().url(url)
            .header("User-Agent", ICloudEndpoints.DESKTOP_USER_AGENT)
            .header("Origin", current.homeEndpoint)
            .header("Referer", "${current.homeEndpoint}/")
        if (ICloudEndpoints.isTrustedAppleHost(host)) builder.header("Cookie", current.cookieHeader)
        val call = clientFor(60).newCall(builder.get().build())
        val response = try {
            call.await()
        } catch (error: IOException) {
            throw TransientAppleException("$step 网络失败：${error.message}", error)
        }
        response.use {
            if (!it.isSuccessful) AppleErrors.throwForHttpFailure(step, it.code, "")
            val body = it.body
            val length = body.contentLength()
            if (length > maxBytes) throw AppleProtocolException("$step：文件过大（$length 字节）。")
            val bytes = body.byteStream().use { stream -> stream.readNBytesCompat(maxBytes + 1) }
            if (bytes.size > maxBytes) throw AppleProtocolException("$step：文件过大。")
            return bytes
        }
    }

    /**
     * Sends [body] to a one-time upload URL Apple handed out. The cookie goes
     * only to Apple's own iCloud hosts.
     */
    suspend fun postSignedUpload(signedUrl: String, body: RequestBody, readTimeoutSeconds: Long): AppleResponse {
        val host = hostOf(signedUrl)
        require(signedUrl.startsWith("https://", ignoreCase = true) && ICloudEndpoints.isTrustedSignedUploadHost(host)) {
            "Apple 返回的上传地址不在受信任域名内。"
        }
        val current = session.current()
        val builder = Request.Builder().url(signedUrl)
            .header("Content-Type", "text/plain")
            .header("Origin", current.homeEndpoint)
            .header("Referer", "${current.homeEndpoint}/photos/")
            .header("User-Agent", ICloudEndpoints.DESKTOP_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .post(body)
        if (ICloudEndpoints.isTrustedAppleHost(host)) builder.header("Cookie", current.cookieHeader)
        return execute(builder.build(), readTimeoutSeconds, "上传文件", uploadClient(readTimeoutSeconds))
    }

    private fun baseRequest(url: String, readTimeoutSeconds: Long): Request.Builder {
        val current = session.current()
        require(ICloudEndpoints.isTrustedAppleHost(hostOf(url))) { "请求地址不在 iCloud 域名内。" }
        return Request.Builder().url(url)
            .header("Cookie", current.cookieHeader)
            .header("Origin", current.homeEndpoint)
            .header("Referer", "${current.homeEndpoint}/photos/")
            .header("User-Agent", ICloudEndpoints.DESKTOP_USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.7")
            .header("Cache-Control", "no-cache")
    }

    private suspend fun execute(
        request: Request,
        readTimeoutSeconds: Long,
        step: String,
        client: OkHttpClient = clientFor(readTimeoutSeconds),
    ): AppleResponse {
        val response = try {
            client.newCall(request).await()
        } catch (error: IOException) {
            throw TransientAppleException("$step 网络失败：${error.message ?: error.javaClass.simpleName}", error)
        }
        response.use {
            mergeCookies(it)
            val text = try {
                it.body.string()
            } catch (error: IOException) {
                throw TransientAppleException("$step 读取响应失败：${error.message}", error)
            }
            return AppleResponse(it.code, text)
        }
    }

    private fun mergeCookies(response: Response) {
        if (!ICloudEndpoints.isTrustedAppleHost(response.request.url.host)) return
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) session.applySetCookies(setCookies)
    }

    companion object {
        private val TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8".toMediaType()

        private val baseClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build()
        }

        fun clientFor(readTimeoutSeconds: Long): OkHttpClient =
            if (readTimeoutSeconds == 45L) {
                baseClient
            } else {
                baseClient.newBuilder().readTimeout(readTimeoutSeconds, TimeUnit.SECONDS).build()
            }

        /** Uploads: a stalled connection fails after 60 s without progress; the whole call has a size-based cap. */
        fun uploadClient(callTimeoutSeconds: Long): OkHttpClient =
            baseClient.newBuilder()
                .writeTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build()
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
