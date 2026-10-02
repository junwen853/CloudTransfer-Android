package io.github.junwen853.cloudtransfer.icloud

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Base64

/** One original file in iCloud Photos (a CPLMaster paired with its CPLAsset). */
data class RemoteItem(
    /** Unique per zone and record; the primary key of the local index. */
    val identity: String,
    val zone: String,
    val recordName: String,
    val checksum: String,
    val size: Long,
    /** Live Photo motion part, if any. */
    val companionChecksum: String,
    val companionSize: Long,
    val filename: String,
    /** Capture time in UTC milliseconds (CPLAsset.assetDate), 0 if unknown. */
    val assetDate: Long,
    /** Offset of the capture time zone in seconds east of UTC, or null if unknown. */
    val timeZoneOffsetSeconds: Int?,
    val width: Int,
    val height: Int,
    val hidden: Boolean,
)

class ParsedPage(
    val items: List<RemoteItem>,
    /** CPLAsset record names in page order. */
    val assetIds: List<String>,
)

object RemoteRecords {
    const val PRIMARY_ZONE = "PrimarySync"

    /** Fields requested for every indexed record. */
    val INDEX_KEYS = listOf(
        "filenameEnc", "resOriginalRes", "resOriginalVidComplRes", "masterRef",
        "assetDate", "timeZoneOffset", "resOriginalWidth", "resOriginalHeight",
    )

    /**
     * Pairs the CPLAsset/CPLMaster records of one query page. A page where a
     * pair is broken cannot prove what is in iCloud and is rejected.
     */
    fun parsePage(records: JSONArray, zoneKey: String, hidden: Boolean): ParsedPage {
        val masters = LinkedHashMap<String, JSONObject>()
        val assets = ArrayList<JSONObject>()
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            when (record.text("recordType")) {
                "CPLMaster" -> record.text("recordName").takeIf { it.isNotEmpty() }?.let { masters[it] = record }
                "CPLAsset" -> assets += record
            }
        }
        val problems = ArrayList<String>()
        val referenced = HashSet<String>()
        val assetIds = ArrayList<String>()
        val assetByMaster = HashMap<String, JSONObject>()
        for (asset in assets) {
            val assetId = asset.text("recordName")
            val masterId = asset.optJSONObject("fields")?.optJSONObject("masterRef")
                ?.optJSONObject("value").text("recordName")
            if (masterId.isEmpty()) {
                problems += "CPLAsset $assetId 缺少 masterRef"
                continue
            }
            if (!masters.containsKey(masterId)) {
                problems += "缺少 CPLMaster $masterId"
                continue
            }
            referenced += masterId
            assetIds += assetId
            assetByMaster.putIfAbsent(masterId, asset)
        }
        masters.keys.filter { it !in referenced }.forEach { problems += "CPLMaster $it 没有对应的 CPLAsset" }
        if (problems.isNotEmpty()) {
            throw AppleProtocolException("iCloud 索引页的 Asset/Master 配对不完整：${problems.take(3).joinToString("；")}")
        }
        val items = ArrayList<RemoteItem>(masters.size)
        for ((masterId, master) in masters) {
            val fields = master.optJSONObject("fields") ?: JSONObject()
            val assetFields = assetByMaster[masterId]?.optJSONObject("fields") ?: JSONObject()
            val original = fields.optJSONObject("resOriginalRes")?.optJSONObject("value")
            val companion = fields.optJSONObject("resOriginalVidComplRes")?.optJSONObject("value")
            items += RemoteItem(
                identity = identity(zoneKey, masterId),
                zone = zoneKey,
                recordName = masterId,
                checksum = AppleChecksum.normalize(original.text("fileChecksum")),
                size = original?.optLong("size", 0L) ?: 0L,
                companionChecksum = AppleChecksum.normalize(companion.text("fileChecksum")),
                companionSize = companion?.optLong("size", 0L) ?: 0L,
                filename = filename(fields),
                assetDate = assetFields.optJSONObject("assetDate")?.optLong("value", 0L) ?: 0L,
                timeZoneOffsetSeconds = assetFields.optJSONObject("timeZoneOffset")?.let {
                    if (it.has("value")) it.optInt("value") else null
                },
                width = fields.optJSONObject("resOriginalWidth")?.optInt("value", 0) ?: 0,
                height = fields.optJSONObject("resOriginalHeight")?.optInt("value", 0) ?: 0,
                hidden = hidden,
            )
        }
        return ParsedPage(items, assetIds)
    }

    fun identity(zoneKey: String, recordName: String): String =
        if (zoneKey == PRIMARY_ZONE) recordName else "${zoneKey.length}:$zoneKey|$recordName"

    /** The original filename of a CPLMaster, or "" if it cannot be read. */
    fun filename(fields: JSONObject): String {
        val decoded = fields.optJSONObject("filenameEnc_decoded").text("value")
        if (isPlausibleFilename(decoded)) return decoded
        val field = fields.optJSONObject("filenameEnc") ?: return ""
        val encoded = field.text("value")
        if (encoded.isEmpty()) return ""
        val type = field.text("type").uppercase()
        val looksPlain = type == "STRING" ||
            (type != "ENCRYPTED_BYTES" && encoded.indexOf('.') > 0 && !encoded.contains('='))
        if (looksPlain && isPlausibleFilename(encoded)) return encoded
        val candidates = mutableListOf(encoded)
        runCatching { java.net.URLDecoder.decode(encoded, "UTF-8") }.getOrNull()
            ?.takeIf { it != encoded }?.let { candidates += it }
        for (candidate in candidates) {
            val name = decodeBase64Utf8(candidate)?.trim().orEmpty()
            if (isPlausibleFilename(name)) return name
        }
        return ""
    }

    fun isPlausibleFilename(value: String): Boolean {
        val name = value.trim()
        if (name.isEmpty() || name.length > 1024 || name.contains('�') || name.contains('/') || name.contains('\\')) {
            return false
        }
        if (name.any { it.code < 32 || it.code == 127 }) return false
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot >= name.length - 1) return false
        val extension = name.substring(dot + 1)
        return extension.length <= 16 && extension.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }
    }

    fun normalizeFilename(name: String): String =
        Normalizer.normalize(name.trim().substringAfterLast('/').substringAfterLast('\\'), Normalizer.Form.NFC).lowercase()

    private fun decodeBase64Utf8(value: String): String? {
        var normalized = value.trim().replace('-', '+').replace('_', '/')
        when (normalized.length % 4) {
            2 -> normalized += "=="
            3 -> normalized += "="
        }
        val bytes = try {
            Base64.getDecoder().decode(normalized)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** itemCount of a HyperionIndexCountLookup answer (batch or plain query form). */
    fun parseCount(body: JSONObject): Int {
        val batch = body.optJSONArray("batch")
        val records: JSONArray
        val requireType: Boolean
        if (batch != null) {
            if (batch.length() == 0) throw AppleProtocolException("iCloud 照片总数响应没有 batch 结果。")
            records = batch.optJSONObject(0)?.optJSONArray("records") ?: JSONArray()
            requireType = false
        } else {
            records = body.optJSONArray("records") ?: JSONArray()
            requireType = true
        }
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (requireType && record.text("recordType") != "IndexCountResult") continue
            val itemCount = record.optJSONObject("fields")?.optJSONObject("itemCount") ?: continue
            if (!itemCount.has("value")) continue
            val count = itemCount.optLong("value", -1L)
            if (count < 0 || count > Int.MAX_VALUE) throw AppleProtocolException("iCloud 照片总数不是有效数字。")
            return count.toInt()
        }
        throw AppleProtocolException("iCloud 照片总数响应缺少 itemCount。")
    }

    /** Throws if any record of a query answer carries a server error. */
    fun throwIfRecordError(records: JSONArray, step: String) {
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            val code = record.text("serverErrorCode")
            if (code.isNotEmpty() && code != "null") {
                AppleErrors.throwForServiceError(step, "$code：${record.text("reason")}")
            }
        }
    }
}
