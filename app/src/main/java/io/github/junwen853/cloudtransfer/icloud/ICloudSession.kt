package io.github.junwen853.cloudtransfer.icloud

import java.net.URI

/**
 * A signed-in iCloud Photos web session, captured from the iCloud webpage the
 * user signed in to inside the app. The app never sees the Apple ID password.
 */
data class ICloudSession(
    val cookieHeader: String,
    /** Origin of the CloudKit database service, e.g. https://p123-ckdatabasews.icloud.com:443 */
    val databaseUrl: String,
    val dsid: String,
    val clientId: String = "",
    val clientBuildNumber: String = "",
    val clientMasteringNumber: String = "",
    /** Origin of the photosupload service observed in the webpage, or "" to derive it. */
    val photosUploadUrl: String = "",
    val savedAt: Long = System.currentTimeMillis(),
) {
    val isChina: Boolean
        get() = databaseUrl.contains(".icloud.com.cn") || databaseUrl.contains(".apple.com.cn")

    val homeEndpoint: String
        get() = if (isChina) ICloudEndpoints.CHINA_HOME else ICloudEndpoints.GLOBAL_HOME

    val photosUploadBase: String
        get() = ICloudEndpoints.resolvePhotosUploadBase(databaseUrl, photosUploadUrl)

    fun isUsable(): Boolean =
        cookieHeader.isNotBlank() && dsid.matches(Regex("[0-9]+")) &&
            ICloudEndpoints.isDatabaseHost(hostOf(databaseUrl)) && Cookies.hasPhotosPcs(cookieHeader)

    fun maskedDsid(): String = if (dsid.length <= 4) "****" else "****" + dsid.takeLast(4)
}

object ICloudEndpoints {
    const val GLOBAL_HOME = "https://www.icloud.com"
    const val CHINA_HOME = "https://www.icloud.com.cn"
    const val GLOBAL_PHOTOS_PAGE = "https://www.icloud.com/photos/"
    const val CHINA_PHOTOS_PAGE = "https://www.icloud.com.cn/photos/"
    const val PHOTOS_CONTAINER = "com.apple.photos.cloud"
    private const val PHOTOS_PRIVATE_PATH = "/database/1/com.apple.photos.cloud/production/private/"

    /** Desktop Chrome: the iCloud webpage and the services expect a desktop browser. */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    const val FALLBACK_BUILD_NUMBER = "2626Build18"

    private val DATABASE_HOST = Regex("^(?:[a-z0-9-]+-)?ckdatabasews\\.icloud\\.com(?:\\.cn)?$")
    private val PHOTOS_UPLOAD_HOST = Regex("^(?:[a-z0-9-]+-)?(?:uploadphotosws|photosupload)\\.icloud\\.com(?:\\.cn)?$")
    private val BROKEN_UPLOAD_HOST = Regex("^(?:[a-z0-9-]+-)?uploadphotosws\\.icloud\\.com(?:\\.cn)?$")
    private val CLIENT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val BUILD_VALUE = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")

    fun isDatabaseHost(host: String): Boolean = DATABASE_HOST.matches(host.lowercase())

    fun isPhotosUploadHost(host: String): Boolean = PHOTOS_UPLOAD_HOST.matches(host.lowercase())

    fun normalizeClientId(value: String?): String {
        val normalized = value?.trim()?.lowercase().orEmpty()
        return if (CLIENT_ID.matches(normalized)) normalized else ""
    }

    fun validBuildValue(value: String?): String {
        val normalized = value?.trim().orEmpty()
        return if (BUILD_VALUE.matches(normalized)) normalized else ""
    }

    /** https://pNN-ckdatabasews.icloud.com[:port] -> https://pNN-photosupload.icloud.com[:port] */
    fun derivePhotosUploadBase(databaseUrl: String): String {
        val uri = URI(databaseUrl.trim())
        val host = uri.host?.lowercase().orEmpty()
        require(uri.scheme.equals("https", ignoreCase = true) && isDatabaseHost(host)) {
            "不是可信的 iCloud Photos 数据库地址。"
        }
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "https://${host.replace("ckdatabasews", "photosupload")}$port"
    }

    fun resolvePhotosUploadBase(databaseUrl: String, configured: String): String {
        val trimmed = configured.trim()
        if (trimmed.isNotEmpty()) {
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val host = uri?.host?.lowercase().orEmpty()
            val sameRegion = host.endsWith(".icloud.com.cn") == hostOf(databaseUrl).endsWith(".icloud.com.cn")
            if (uri != null && uri.scheme.equals("https", ignoreCase = true) && isPhotosUploadHost(host) &&
                !BROKEN_UPLOAD_HOST.matches(host) && sameRegion
            ) {
                val port = if (uri.port > 0) ":${uri.port}" else ""
                return "https://$host$port"
            }
        }
        return derivePhotosUploadBase(databaseUrl)
    }

    /** Hosts that may receive the session cookie. */
    fun isTrustedAppleHost(host: String): Boolean {
        val normalized = host.lowercase()
        return listOf("icloud.com", "icloud.com.cn", "apple.com", "apple.com.cn").any {
            normalized == it || normalized.endsWith(".$it")
        }
    }

    /** Hosts Apple may hand out as one-time upload URLs. */
    fun isTrustedSignedUploadHost(host: String): Boolean {
        val normalized = host.lowercase()
        return listOf(
            "icloud.com", "icloud.com.cn", "icloud-content.com", "icloud-content.com.cn",
            "apple.com", "apple.com.cn", "cdn-apple.com",
        ).any { normalized == it || normalized.endsWith(".$it") }
    }

    /**
     * Recognises the iCloud Photos webpage's own CloudKit requests, which carry
     * the database host, the account dsid and the web client's build numbers.
     */
    fun parsePhotosDatabaseRequest(url: String): DatabaseRequestEvidence? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !isDatabaseHost(host)) return null
        if (!uri.rawPath.orEmpty().lowercase().startsWith(PHOTOS_PRIVATE_PATH)) return null
        val query = queryParameters(uri.rawQuery.orEmpty())
        val dsid = query["dsid"].orEmpty()
        if (!dsid.matches(Regex("[0-9]+"))) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return DatabaseRequestEvidence(
            databaseUrl = "https://$host$port",
            dsid = dsid,
            clientId = normalizeClientId(query["clientId"]),
            clientBuildNumber = validBuildValue(query["clientBuildNumber"]),
            clientMasteringNumber = validBuildValue(query["clientMasteringNumber"]),
            requestUrl = url,
        )
    }

    /** Origin of a photosupload request made by the webpage, or null. */
    fun parsePhotosUploadRequest(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || !isPhotosUploadHost(host)) return null
        if (!uri.rawPath.orEmpty().lowercase().contains("/photosupload/")) return null
        val port = if (uri.port > 0) ":${uri.port}" else ""
        return "https://$host$port"
    }

    fun queryParameters(rawQuery: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            val equals = pair.indexOf('=')
            if (equals <= 0) return@forEach
            val name = pair.substring(0, equals)
            val value = runCatching {
                java.net.URLDecoder.decode(pair.substring(equals + 1), "UTF-8")
            }.getOrDefault("")
            result.putIfAbsent(name, value.trim())
        }
        return result
    }

    fun buildQueryParams(buildNumber: String, masteringNumber: String, clientId: String, dsid: String): String {
        val params = mutableListOf(
            "clientBuildNumber=${encode(buildNumber)}",
            "clientMasteringNumber=${encode(masteringNumber)}",
        )
        val normalizedClientId = normalizeClientId(clientId)
        if (normalizedClientId.isNotEmpty()) params += "clientId=${encode(normalizedClientId)}"
        params += "dsid=${encode(dsid)}"
        params += "remapEnums=true"
        params += "getCurrentSyncToken=true"
        return params.joinToString("&")
    }

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

data class DatabaseRequestEvidence(
    val databaseUrl: String,
    val dsid: String,
    val clientId: String,
    val clientBuildNumber: String,
    val clientMasteringNumber: String,
    val requestUrl: String,
)

internal fun hostOf(url: String): String = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull().orEmpty()
