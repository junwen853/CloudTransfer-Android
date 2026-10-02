package io.github.junwen853.cloudtransfer.icloud

import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.TimeZone

/**
 * One upload step failed. The flags say what is known about the outcome:
 * [mayHaveBeenAccepted] keeps the duplicate lock until iCloud is checked,
 * [itemRejected] and [localFailure] affect only this file, [retryableTransfer]
 * means the file bytes did not arrive and nothing was added.
 */
class UploadException(
    message: String,
    val mayHaveBeenAccepted: Boolean,
    val itemRejected: Boolean = false,
    val localFailure: Boolean = false,
    val retryableTransfer: Boolean = false,
) : IOException(message)

class SignedUploadResult(val duplicate: Boolean, val singleFile: JSONObject?)

class PutAssetResult(
    val jobId: String,
    val master: String,
    val asset: String,
    val duplicateJob: Boolean,
)

/**
 * Apple's current iCloud Photos web upload (photosupload): ask for a one-time
 * upload URL, send the file bytes there, add the received file to the library
 * (putAsset) and wait until Apple has processed it.
 */
class PhotosUploader(
    private val http: AppleHttp,
    private val session: SessionAccess,
) {
    companion object {
        const val DUPLICATE_JOB_STATUS = 409
        private const val REQUEST_ATTEMPTS = 3
        private const val MIN_BYTES_PER_SECOND = 48 * 1024
        private const val MIN_TRANSFER_SECONDS = 10 * 60L
        private const val MAX_TRANSFER_SECONDS = 4 * 60 * 60L
        private val IMPORT_GROUP = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

        fun transferTimeoutSeconds(size: Long): Long =
            (size / MIN_BYTES_PER_SECOND + 1).coerceIn(MIN_TRANSFER_SECONDS, MAX_TRANSFER_SECONDS)

        /** Apple needs longer to process a long video before its job reports 100%. */
        fun processingWaitMs(size: Long): Long =
            minOf(60 * 60 * 1000L, 10 * 60 * 1000L + ((size + 100L * 1024 * 1024 - 1) / (100L * 1024 * 1024)) * 3 * 60 * 1000L)

        fun httpMayHaveBeenAccepted(status: Int): Boolean =
            status <= 0 || status >= 500 || status == 408 || status == 409 || status == 425 || status == 429

        /** A 4xx answer, other than session, conflict and throttling codes, that refuses one request. */
        fun isClientRejection(status: Int): Boolean =
            status in 400..499 && !AppleErrors.isAuthStatus(status) && status !in setOf(408, 409, 425, 429)

        /**
         * createUploadUrl carries only the zone and the byte count, so apart from
         * "too large" and "unsupported type" its 4xx answers concern the account.
         */
        fun endpointRejectsItem(endpoint: String, status: Int): Boolean =
            if (endpoint == "createUploadUrl") status == 413 || status == 415 else isClientRejection(status)

        fun isValidImportGroup(value: String): Boolean = IMPORT_GROUP.matches(value)

        /** JavaScript getTimezoneOffset(): minutes to add to local time to get UTC. */
        fun jsTimezoneOffsetMinutes(timeMs: Long, zone: TimeZone = TimeZone.getDefault()): Int =
            -zone.getOffset(timeMs) / 60_000

        fun parseCreateUploadUrl(body: String, uploadId: String): String {
            val json = parseObject(body, "创建上传地址")
            val error = AppleErrors.serviceError(json)
            if (error.isNotEmpty()) {
                if (AppleErrors.hasAuthMarker(error)) throw SessionExpiredException()
                if (AppleErrors.hasAccessDeniedMarker(error)) throw WebAccessDeniedException()
                throw UploadException("创建上传地址返回错误：$error", mayHaveBeenAccepted = false)
            }
            val url = json.optJSONObject("uploadUrls").text(uploadId)
            if (url.isEmpty()) throw UploadException("Apple 没有返回本文件的上传地址。", mayHaveBeenAccepted = false)
            if (!url.startsWith("https://", ignoreCase = true) || !ICloudEndpoints.isTrustedSignedUploadHost(hostOf(url))) {
                throw UploadException("Apple 返回的上传地址不在受信任域名内。", mayHaveBeenAccepted = false)
            }
            return url
        }

        fun parseSignedUpload(body: String, expectedSize: Long): SignedUploadResult {
            val json = parseObject(body, "上传文件")
            val error = AppleErrors.serviceError(json)
            if (error.isNotEmpty()) {
                if (AppleErrors.hasAuthMarker(error)) throw SessionExpiredException()
                throw UploadException("上传文件返回错误：$error", mayHaveBeenAccepted = false)
            }
            val singleFile = json.optJSONObject("singleFile")
                ?: throw UploadException("Apple 上传回执缺少 singleFile。", mayHaveBeenAccepted = false)
            validateSingleFile(singleFile, expectedSize)
            return SignedUploadResult(json.optBoolean("isDuplicate", false), singleFile)
        }

        fun validateSingleFile(singleFile: JSONObject, expectedSize: Long) {
            val size = singleFile.optLong("size", 0L)
            if (singleFile.text("fileChecksum").isBlank() || singleFile.text("referenceChecksum").isBlank() ||
                singleFile.text("receipt").isBlank() || size <= 0
            ) {
                throw UploadException("Apple 上传回执缺少 fileChecksum、referenceChecksum、receipt 或 size。", mayHaveBeenAccepted = false)
            }
            if (expectedSize > 0 && size != expectedSize) {
                throw UploadException("Apple 收到 $size 字节，本机发送了 $expectedSize 字节，没有加入照片库。", mayHaveBeenAccepted = false)
            }
        }

        fun parsePutAsset(body: String): PutAssetResult {
            val decoded = try {
                org.json.JSONTokener(body).nextValue()
            } catch (_: JSONException) {
                throw UploadException("Apple 照片入库响应不是 JSON：${AppleErrors.compact(body)}", mayHaveBeenAccepted = true)
            }
            if (decoded !is JSONArray) {
                val error = (decoded as? JSONObject)?.let { AppleErrors.serviceError(it) }.orEmpty()
                if (AppleErrors.hasAuthMarker(error)) throw SessionExpiredException()
                throw UploadException("Apple 照片入库响应格式不对：${AppleErrors.compact(body)}", mayHaveBeenAccepted = true)
            }
            if (decoded.length() != 1) {
                throw UploadException("Apple 照片入库响应数量异常：${decoded.length()}。", mayHaveBeenAccepted = true)
            }
            val item = decoded.optJSONObject(0) ?: JSONObject()
            val jobId = item.text("uploadJobId")
            val master = item.text("cplMaster")
            val asset = item.text("cplAsset")
            if (jobId.isNotEmpty()) {
                if (master.isEmpty() || asset.isEmpty()) {
                    throw UploadException("Apple 入库响应缺少 CPLMaster/CPLAsset。", mayHaveBeenAccepted = true)
                }
                return PutAssetResult(jobId, master, asset, duplicateJob = false)
            }
            val response = item.optJSONObject("response") ?: JSONObject()
            val status = response.optInt("status", 0)
            val message = response.text("errorMessage")
            if (AppleErrors.isAuthStatus(status)) throw SessionExpiredException()
            if (status == DUPLICATE_JOB_STATUS) return PutAssetResult("", master, asset, duplicateJob = true)
            val retryable = response.optBoolean("isRetryable", false)
            val detail = if (message.isNotEmpty()) "：$message" else if (status > 0) "：状态 $status" else ""
            val explicitRefusal = !retryable && (if (status > 0) isClientRejection(status) else message.isNotEmpty())
            throw PutAssetRefusal(
                if (explicitRefusal) "Apple 明确没有把文件加入照片库$detail" else "Apple 没有返回入库任务$detail；结果不确定，先不重传。",
                retryable = retryable,
                explicitRefusal = explicitRefusal,
            )
        }

        /** Progress (0-100) of an upload job; throws for an explicit Apple error code. */
        fun parseUploadStatus(body: String, jobId: String): Double {
            val json = parseObject(body, "查询处理进度", mayHaveBeenAccepted = true)
            val error = AppleErrors.serviceError(json)
            if (error.isNotEmpty()) {
                if (AppleErrors.hasAuthMarker(error)) throw SessionExpiredException()
                throw UploadException("查询处理进度返回错误：$error", mayHaveBeenAccepted = true)
            }
            val status = json.optJSONObject(jobId)
                ?: throw UploadException("Apple 处理进度里没有任务 $jobId。", mayHaveBeenAccepted = true)
            val code = status.text("errorCode")
            if (code.isNotEmpty() && code != "null" && code != "0") {
                val numeric = code.toIntOrNull()
                if (numeric != null && AppleErrors.isAuthStatus(numeric)) throw SessionExpiredException()
                if (numeric != null && AppleErrors.isRetryableStatus(numeric)) {
                    throw UploadException("Apple 处理时暂时出错（$code）；结果不确定，先不重传。", mayHaveBeenAccepted = true)
                }
                // An explicit error code is Apple's final answer: the file was not added.
                throw UploadException("Apple 处理失败，错误码 $code。", mayHaveBeenAccepted = false, itemRejected = true)
            }
            if (!status.has("progress")) throw UploadException("Apple 处理进度缺少 progress。", mayHaveBeenAccepted = true)
            return status.optDouble("progress", 0.0)
        }

        private fun parseObject(body: String, step: String, mayHaveBeenAccepted: Boolean = false): JSONObject = try {
            JSONObject(body)
        } catch (_: JSONException) {
            throw UploadException("$step 响应不是 JSON：${AppleErrors.compact(body)}", mayHaveBeenAccepted)
        }
    }

    class PutAssetRefusal(message: String, val retryable: Boolean, val explicitRefusal: Boolean) : IOException(message)

    private fun endpointUrl(endpoint: String): String {
        val current = session.current()
        return "${current.photosUploadBase.trimEnd('/')}/photosupload/$endpoint?dsid=${ICloudEndpoints.encode(current.dsid)}"
    }

    /**
     * POSTs to a photosupload endpoint. Network failures and server errors are
     * retried when [retryTransient]; [mayHaveBeenAccepted] says whether a lost
     * answer may hide a change Apple already made.
     */
    private suspend fun postEndpoint(
        endpoint: String,
        payload: JSONObject,
        step: String,
        mayHaveBeenAccepted: Boolean,
        retryTransient: Boolean = true,
        failureKeepsLock: Boolean = false,
    ): String {
        var lastMessage = ""
        for (attempt in 1..REQUEST_ATTEMPTS) {
            val response = try {
                http.postJson(endpointUrl(endpoint), payload.toString(), readTimeoutSeconds = 120, step = step)
            } catch (error: TransientAppleException) {
                lastMessage = error.message.orEmpty()
                if (!retryTransient || attempt >= REQUEST_ATTEMPTS) {
                    throw UploadException(lastMessage, mayHaveBeenAccepted = failureKeepsLock || mayHaveBeenAccepted)
                }
                delay(minOf(500L shl (attempt - 1), 10_000L))
                continue
            }
            if (response.code in 200..299) return response.body
            if (AppleErrors.hasAccessDeniedMarker(response.body)) throw WebAccessDeniedException()
            if (AppleErrors.isAuthStatus(response.code)) throw SessionExpiredException()
            lastMessage = "$step HTTP ${response.code}：${AppleErrors.compact(response.body)}"
            if (!(retryTransient && attempt < REQUEST_ATTEMPTS && AppleErrors.isRetryableStatus(response.code))) {
                val accepted = failureKeepsLock || (mayHaveBeenAccepted && httpMayHaveBeenAccepted(response.code))
                throw UploadException(
                    lastMessage,
                    mayHaveBeenAccepted = accepted,
                    itemRejected = !accepted && endpointRejectsItem(endpoint, response.code),
                )
            }
            delay(minOf(500L shl (attempt - 1), 10_000L))
        }
        throw UploadException(lastMessage.ifEmpty { "$step 失败。" }, mayHaveBeenAccepted)
    }

    /** A one-time URL for sending [size] bytes. Nothing is added to the library yet. */
    suspend fun createUploadUrl(size: Long): String {
        val uploadId = java.util.UUID.randomUUID().toString().lowercase()
        val payload = JSONObject()
            .put("zoneName", RemoteRecords.PRIMARY_ZONE)
            .put("assets", JSONObject().put(uploadId, size))
        val body = postEndpoint("createUploadUrl", payload, "创建上传地址", mayHaveBeenAccepted = false)
        return parseCreateUploadUrl(body, uploadId)
    }

    /** Sends the file bytes to the one-time URL. */
    suspend fun sendFile(signedUrl: String, body: VerifyingFileBody, filename: String): SignedUploadResult {
        val response = try {
            http.postSignedUpload(signedUrl, body, transferTimeoutSeconds(body.contentLength()))
        } catch (error: TransientAppleException) {
            if (body.localFailure != null) {
                throw UploadException("$filename ${body.localFailure}", mayHaveBeenAccepted = false, localFailure = true)
            }
            throw UploadException(
                "上传 $filename 时网络中断，还没有加入照片库：${error.message}",
                mayHaveBeenAccepted = false,
                retryableTransfer = true,
            )
        }
        if (response.code !in 200..299) {
            if (AppleErrors.hasAccessDeniedMarker(response.body)) throw WebAccessDeniedException()
            if (AppleErrors.isAuthStatus(response.code)) throw SessionExpiredException()
            throw UploadException(
                "上传 $filename HTTP ${response.code}：${AppleErrors.compact(response.body)}",
                mayHaveBeenAccepted = false,
                itemRejected = isClientRejection(response.code),
                retryableTransfer = AppleErrors.isRetryableStatus(response.code),
            )
        }
        return parseSignedUpload(response.body, body.contentLength())
    }

    /**
     * Adds a received file to the library. Apple remembers [importGroup]: sending
     * the same group again cannot create a second photo (it answers DuplicateJob).
     */
    suspend fun putAsset(filename: String, captureTimeMs: Long, singleFile: JSONObject, importGroup: String): PutAssetResult {
        require(isValidImportGroup(importGroup)) { "importGroup 无效。" }
        val timestamp = if (captureTimeMs > 0) captureTimeMs else System.currentTimeMillis()
        val payload = JSONObject()
            .put("zoneName", RemoteRecords.PRIMARY_ZONE)
            .put(
                "files",
                JSONArray().put(
                    JSONObject()
                        .put("fileName", filename)
                        .put("lastModDate", timestamp)
                        .put("timeZoneOffset", jsTimezoneOffsetMinutes(timestamp))
                        .put("singleFileUploadRequest", singleFile),
                ),
            )
            .put("localTimeZoneId", TimeZone.getDefault().id.ifEmpty { "UTC" })
            .put("importGroup", importGroup)
        var lastRefusal: PutAssetRefusal? = null
        for (attempt in 1..REQUEST_ATTEMPTS) {
            val body = postEndpoint("putAsset", payload, "加入照片库", mayHaveBeenAccepted = true)
            try {
                return parsePutAsset(body)
            } catch (refusal: PutAssetRefusal) {
                lastRefusal = refusal
                if (!refusal.retryable || attempt >= REQUEST_ATTEMPTS) break
                delay(minOf(1000L * attempt, 3000L))
            }
        }
        val refusal = lastRefusal ?: throw UploadException("Apple 没有返回入库任务。", mayHaveBeenAccepted = true)
        throw UploadException(
            refusal.message.orEmpty(),
            mayHaveBeenAccepted = !refusal.explicitRefusal,
            itemRejected = refusal.explicitRefusal,
        )
    }

    /** Waits until Apple reports the job done (100%). */
    suspend fun waitForJob(jobId: String, size: Long, maxWaitMs: Long = 0L, onProgress: (Double) -> Unit) {
        val startedAt = System.currentTimeMillis()
        val maximum = if (maxWaitMs > 0) minOf(maxWaitMs, processingWaitMs(size)) else processingWaitMs(size)
        var failures = 0
        while (System.currentTimeMillis() - startedAt < maximum) {
            try {
                val body = postEndpoint(
                    "uploadStatus", JSONObject().put("uploadJobIds", JSONArray().put(jobId)), "查询处理进度",
                    mayHaveBeenAccepted = true, failureKeepsLock = true,
                )
                val progress = parseUploadStatus(body, jobId)
                failures = 0
                onProgress(progress.coerceIn(0.0, 100.0))
                if (progress >= 100.0) return
            } catch (error: UploadException) {
                if (!error.mayHaveBeenAccepted) throw error
                failures++
                if (failures >= REQUEST_ATTEMPTS) {
                    throw UploadException("入库任务已提交，但连续查询进度失败：${error.message}", mayHaveBeenAccepted = true)
                }
            }
            delay(1000)
        }
        throw UploadException("入库任务已提交，但等待 Apple 处理超时。", mayHaveBeenAccepted = true)
    }
}

/**
 * Streams a file and computes its Apple checksum while sending, so the bytes
 * that went out are exactly the bytes that were checked.
 */
class VerifyingFileBody(
    private val open: () -> InputStream,
    private val size: Long,
    private val onProgress: (sent: Long, total: Long) -> Unit,
) : RequestBody() {
    @Volatile
    var sentChecksum: String = ""
        private set

    /** Set when the phone, not the network, made the transfer fail. */
    @Volatile
    var localFailure: String? = null
        private set

    override fun contentType() = "text/plain".toMediaType()

    override fun contentLength(): Long = size

    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val input = try {
            open()
        } catch (error: Exception) {
            localFailure = "无法打开：${error.message}"
            throw IOException(localFailure, error)
        }
        input.use { stream ->
            val digest = AppleChecksum.newDigest()
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            var lastReported = 0L
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (error: IOException) {
                    localFailure = "读取失败：${error.message}"
                    throw error
                }
                if (read < 0) break
                if (total + read > size) {
                    localFailure = "在上传时变大了，请重新检查。"
                    throw IOException(localFailure)
                }
                digest.update(buffer, 0, read)
                sink.write(buffer, 0, read)
                total += read
                if (total - lastReported >= 512 * 1024 || total == size) {
                    lastReported = total
                    onProgress(total, size)
                }
            }
            if (total != size) {
                localFailure = "在上传时变小了，请重新检查。"
                throw IOException(localFailure)
            }
            sentChecksum = AppleChecksum.finish(digest)
        }
    }
}
