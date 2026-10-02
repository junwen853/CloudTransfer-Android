package io.github.junwen853.cloudtransfer.icloud

import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A CloudKit zone of the iCloud Photos container. */
data class PhotoZone(
    val scope: String,
    val zoneName: String,
    val ownerRecordName: String = "",
    val zoneType: String = "",
) {
    /** Key stored with every indexed record of this zone. */
    val key: String
        get() = if (scope == "private" && zoneName == RemoteRecords.PRIMARY_ZONE) {
            RemoteRecords.PRIMARY_ZONE
        } else {
            listOf(scope, zoneName, ownerRecordName, zoneType).joinToString("|") { "${it.length}:$it" }
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("zoneName", zoneName)
        if (ownerRecordName.isNotEmpty()) put("ownerRecordName", ownerRecordName)
        if (zoneType.isNotEmpty()) put("zoneType", zoneType)
    }

    companion object {
        val PRIMARY = PhotoZone("private", RemoteRecords.PRIMARY_ZONE, zoneType = "REGULAR_CUSTOM_ZONE")

        /** Inverse of [key]; null for a malformed key. */
        fun fromKey(key: String): PhotoZone? {
            if (key == RemoteRecords.PRIMARY_ZONE) return PRIMARY
            val parts = ArrayList<String>()
            var position = 0
            while (position < key.length) {
                val colon = key.indexOf(':', position)
                if (colon < 0) return null
                val length = key.substring(position, colon).toIntOrNull() ?: return null
                val end = colon + 1 + length
                if (end > key.length) return null
                parts += key.substring(colon + 1, end)
                position = end
                if (position < key.length) {
                    if (key[position] != '|') return null
                    position++
                }
            }
            if (parts.size != 4) return null
            return PhotoZone(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

/**
 * Reads the iCloud Photos library through the same CloudKit web service the
 * iCloud Photos webpage uses.
 */
class CloudKitClient(
    private val http: AppleHttp,
    private val session: SessionAccess,
) {
    private var buildNumber: String = ""
    private var masteringNumber: String = ""

    companion object {
        const val LIST_VISIBLE = "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted"
        const val LIST_HIDDEN = "CPLAssetAndMasterHiddenByAssetDate"
        const val LIST_RECENT = "CPLAssetAndMasterByAddedDate"
        const val COUNT_VISIBLE = "CPLAssetByAssetDateWithoutHiddenOrDeleted"
        const val COUNT_HIDDEN = "CPLAssetHiddenByAssetDate"
        const val PAGE_PHOTOS = 100
        private const val SHARED_LIBRARY_PREFIX = "SharedSync-"
        private const val PAGE_ATTEMPTS = 3
    }

    /** The web client's build numbers; Apple rejects requests from unknown builds. */
    private suspend fun ensureBuildInfo() {
        if (buildNumber.isNotEmpty()) return
        val current = session.current()
        var build = ""
        var mastering = ""
        try {
            val page = http.get("${current.homeEndpoint}/applications/photos3/current/en-us/", step = "读取 iCloud 网页版本")
            if (page.code in 200..299) {
                build = ICloudEndpoints.validBuildValue(htmlAttribute(page.body, "data-cw-private-build-number"))
                mastering = ICloudEndpoints.validBuildValue(htmlAttribute(page.body, "data-cw-private-mastering-number"))
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // The build numbers seen in the webpage or the built-in fallback still work.
        }
        if (build.isEmpty()) {
            build = ICloudEndpoints.validBuildValue(current.clientBuildNumber)
            mastering = ICloudEndpoints.validBuildValue(current.clientMasteringNumber)
        }
        if (build.isEmpty()) build = ICloudEndpoints.FALLBACK_BUILD_NUMBER
        buildNumber = build
        masteringNumber = mastering.ifEmpty { build }
    }

    private suspend fun serviceUrl(scope: String, path: String, extra: String = ""): String {
        ensureBuildInfo()
        val current = session.current()
        val base = current.databaseUrl.trimEnd('/')
        val params = ICloudEndpoints.buildQueryParams(buildNumber, masteringNumber, current.clientId, current.dsid)
        val normalizedScope = if (scope == "shared") "shared" else "private"
        return "$base/database/1/${ICloudEndpoints.PHOTOS_CONTAINER}/production/$normalizedScope/$path?$params$extra"
    }

    private suspend fun postForJson(url: String, body: JSONObject, step: String): JSONObject {
        val response = http.postJson(url, body.toString(), step = step)
        if (response.code !in 200..299) AppleErrors.throwForHttpFailure(step, response.code, response.body)
        val json = try {
            JSONObject(response.body)
        } catch (_: JSONException) {
            throw AppleProtocolException("$step 响应不是 JSON：${AppleErrors.compact(response.body)}")
        }
        val error = AppleErrors.serviceError(json)
        if (error.isNotEmpty()) AppleErrors.throwForServiceError(step, error)
        return json
    }

    /** Number of photos and videos Apple reports for one index of a zone. */
    suspend fun count(indexId: String, zone: PhotoZone = PhotoZone.PRIMARY): Int {
        val query = JSONObject()
            .put("recordType", "HyperionIndexCountLookup")
            .put(
                "filterBy",
                JSONArray().put(
                    JSONObject()
                        .put("fieldName", "indexCountID")
                        .put("comparator", "IN")
                        .put("fieldValue", JSONObject().put("type", "STRING_LIST").put("value", JSONArray().put(indexId))),
                ),
            )
        val step = "读取 iCloud 照片总数"
        try {
            val batch = JSONObject().put(
                "batch",
                JSONArray().put(
                    JSONObject().put("resultsLimit", 1).put("query", query).put("zoneWide", true).put("zoneID", zone.toJson()),
                ),
            )
            val json = postForJson(serviceUrl(zone.scope, "internal/records/query/batch", "&parallel=true"), batch, step)
            val first = json.optJSONArray("batch")?.optJSONObject(0)
            if (first != null) {
                val error = AppleErrors.serviceError(first)
                if (error.isNotEmpty()) AppleErrors.throwForServiceError(step, error)
                RemoteRecords.throwIfRecordError(first.optJSONArray("records") ?: JSONArray(), step)
            }
            return RemoteRecords.parseCount(json)
        } catch (error: AppleProtocolException) {
            // Older accounts answer only the plain query form.
            val json = postForJson(
                serviceUrl(zone.scope, "records/query"),
                JSONObject().put("query", query).put("zoneID", zone.toJson()),
                step,
            )
            RemoteRecords.throwIfRecordError(json.optJSONArray("records") ?: JSONArray(), step)
            return RemoteRecords.parseCount(json)
        }
    }

    /** iCloud Shared Photo Library zones the account can read (private and shared scope). */
    suspend fun sharedLibraryZones(): List<PhotoZone> {
        val zones = LinkedHashMap<String, PhotoZone>()
        for (scope in listOf("private", "shared")) {
            val step = if (scope == "shared") "读取共享图库范围" else "读取个人图库范围"
            val url = serviceUrl(scope, "zones/list", "&excludeSCZones=true")
            var response = http.get(url, step = step)
            if (response.code in setOf(400, 404, 405, 501)) {
                response = http.postJson(url, "{}", step = step)
            }
            if (response.code !in 200..299) AppleErrors.throwForHttpFailure(step, response.code, response.body)
            val json = try {
                JSONObject(response.body)
            } catch (_: JSONException) {
                throw AppleProtocolException("$step 响应不是 JSON。")
            }
            val error = AppleErrors.serviceError(json)
            if (error.isNotEmpty()) AppleErrors.throwForServiceError(step, error)
            val list = json.optJSONArray("zones") ?: throw AppleProtocolException("$step 响应缺少 zones。")
            for (index in 0 until list.length()) {
                val entry = list.optJSONObject(index) ?: continue
                if (entry.optBoolean("deleted", false)) continue
                val zoneId = entry.optJSONObject("zoneID") ?: continue
                val name = zoneId.text("zoneName")
                if (!name.startsWith(SHARED_LIBRARY_PREFIX)) continue
                val zone = PhotoZone(
                    scope = scope,
                    zoneName = name,
                    ownerRecordName = zoneId.text("ownerRecordName"),
                    zoneType = zoneId.text("zoneType"),
                )
                zones.putIfAbsent(zone.key, zone)
            }
        }
        return zones.values.toList()
    }

    /**
     * One page of up to [PAGE_PHOTOS] photos (twice as many records: each
     * photo is a CPLAsset plus its CPLMaster). Network failures and server-side
     * errors are retried; session and format errors are not.
     */
    suspend fun fetchPage(
        startRank: Int,
        recordType: String,
        zone: PhotoZone = PhotoZone.PRIMARY,
        direction: String = "ASCENDING",
        photos: Int = PAGE_PHOTOS,
        desiredKeys: List<String> = RemoteRecords.INDEX_KEYS,
    ): JSONArray {
        val body = JSONObject()
            .put(
                "query",
                JSONObject()
                    .put("recordType", recordType)
                    .put(
                        "filterBy",
                        JSONArray()
                            .put(
                                JSONObject().put("fieldName", "startRank").put("comparator", "EQUALS")
                                    .put("fieldValue", JSONObject().put("type", "INT64").put("value", startRank)),
                            )
                            .put(
                                JSONObject().put("fieldName", "direction").put("comparator", "EQUALS")
                                    .put("fieldValue", JSONObject().put("type", "STRING").put("value", direction)),
                            ),
                    ),
            )
            .put("resultsLimit", photos * 2)
            .put("desiredKeys", JSONArray(desiredKeys))
            .put("zoneID", zone.toJson())
        val step = "读取 iCloud 照片索引（第 $startRank 张起）"
        var attempt = 1
        while (true) {
            try {
                val json = postForJson(serviceUrl(zone.scope, "records/query"), body, step)
                val records = json.optJSONArray("records") ?: JSONArray()
                RemoteRecords.throwIfRecordError(records, step)
                return records
            } catch (error: TransientAppleException) {
                if (attempt >= PAGE_ATTEMPTS) throw error
                delay(1500L * attempt)
                attempt++
            }
        }
    }

    /** Looks records up by name; missing records come back with serverErrorCode NOT_FOUND. */
    suspend fun lookup(
        recordNames: List<String>,
        zone: PhotoZone = PhotoZone.PRIMARY,
        desiredKeys: List<String>? = null,
    ): JSONArray {
        val records = JSONArray()
        recordNames.forEach { records.put(JSONObject().put("recordName", it)) }
        val body = JSONObject().put("records", records).put("zoneID", zone.toJson())
        if (desiredKeys != null) body.put("desiredKeys", JSONArray(desiredKeys))
        val json = postForJson(serviceUrl(zone.scope, "records/lookup"), body, "查询 iCloud 照片记录")
        return json.optJSONArray("records") ?: JSONArray()
    }

    private fun htmlAttribute(html: String, name: String): String =
        Regex("$name\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim().orEmpty()
}
