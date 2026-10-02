package io.github.junwen853.cloudtransfer

import io.github.junwen853.cloudtransfer.icloud.AppleChecksum
import io.github.junwen853.cloudtransfer.icloud.AppleErrors
import io.github.junwen853.cloudtransfer.icloud.AppleProtocolException
import io.github.junwen853.cloudtransfer.icloud.Cookies
import io.github.junwen853.cloudtransfer.icloud.ICloudEndpoints
import io.github.junwen853.cloudtransfer.icloud.ICloudSession
import io.github.junwen853.cloudtransfer.icloud.PhotoZone
import io.github.junwen853.cloudtransfer.icloud.PhotosUploader
import io.github.junwen853.cloudtransfer.icloud.RemoteRecords
import io.github.junwen853.cloudtransfer.icloud.SessionExpiredException
import io.github.junwen853.cloudtransfer.icloud.TransientAppleException
import io.github.junwen853.cloudtransfer.icloud.UploadException
import io.github.junwen853.cloudtransfer.icloud.WebAccessDeniedException
import io.github.junwen853.cloudtransfer.icloud.text
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.TimeZone

class ICloudProtocolTest {

    @Test
    fun appleChecksumMatchesKnownValues() {
        assertEquals("AcW96XzA8CMFcrVWfJyAAy8aWWE6", AppleChecksum.of("hello".toByteArray()))
        assertEquals("AW2cWT2PxzWixMKuuNMTHJ8CJ3sH", AppleChecksum.of(ByteArray(0)))
        val bytes = ByteArray(3_000_000) { (it * 31 % 251).toByte() }
        val (streamed, size) = AppleChecksum.of(ByteArrayInputStream(bytes))
        assertEquals(AppleChecksum.of(bytes), streamed)
        assertEquals(3_000_000L, size)
    }

    @Test
    fun normalizeAcceptsOnlySchemeOneChecksums() {
        assertEquals("AV6nbxjJYn7AL1szY2QnBcr9EtCf", AppleChecksum.normalize(" AV6nbxjJYn7AL1szY2QnBcr9EtCf "))
        val schemeTwo = Base64.getEncoder().encodeToString(ByteArray(21).also { it[0] = 2 })
        assertEquals("", AppleChecksum.normalize(schemeTwo))
        assertEquals("", AppleChecksum.normalize("short"))
        assertEquals("", AppleChecksum.normalize(null))
    }

    @Test
    fun cookiesMergeAndRotate() {
        val merged = Cookies.merge("a=1; X-APPLE-WEBAUTH-TOKEN=old", "X-APPLE-WEBAUTH-TOKEN=new; b=2")
        assertEquals("a=1; X-APPLE-WEBAUTH-TOKEN=new; b=2", merged)
        val rotated = Cookies.applySetCookies(
            merged,
            listOf(
                "X-APPLE-WEBAUTH-TOKEN=rotated; Domain=.icloud.com; Path=/; Secure; HttpOnly",
                "X-APPLE-WEBAUTH-PCS-Photos=pcs; Path=/",
                "tracking=ignored; Path=/",
            ),
        )
        assertEquals("rotated", Cookies.value(rotated, "X-APPLE-WEBAUTH-TOKEN"))
        assertTrue(Cookies.hasPhotosPcs(rotated))
        assertEquals("", Cookies.value(rotated, "tracking"))
        val removed = Cookies.applySetCookies(rotated, listOf("X-APPLE-WEBAUTH-PCS-Photos=\"\"; Max-Age=0"))
        assertFalse(Cookies.hasPhotosPcs(removed))
        val expired = Cookies.applySetCookies(rotated, listOf("X-APPLE-WEBAUTH-TOKEN=x; Expires=Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals("", Cookies.value(expired, "X-APPLE-WEBAUTH-TOKEN"))
    }

    @Test
    fun recognisesTheWebpagesDatabaseRequest() {
        val evidence = ICloudEndpoints.parsePhotosDatabaseRequest(
            "https://p123-ckdatabasews.icloud.com:443/database/1/com.apple.photos.cloud/production/private/records/query" +
                "?clientBuildNumber=2626Build21&clientMasteringNumber=2626Build21&clientId=ABCDEF01-2345-6789-ABCD-EF0123456789&dsid=12345678",
        )!!
        assertEquals("https://p123-ckdatabasews.icloud.com:443", evidence.databaseUrl)
        assertEquals("12345678", evidence.dsid)
        assertEquals("abcdef01-2345-6789-abcd-ef0123456789", evidence.clientId)
        assertEquals("2626Build21", evidence.clientBuildNumber)
        assertNull(ICloudEndpoints.parsePhotosDatabaseRequest("https://evil.example.com/database/1/com.apple.photos.cloud/production/private/x?dsid=1"))
        assertNull(ICloudEndpoints.parsePhotosDatabaseRequest("https://p1-ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production/private/x?dsid=abc"))
        assertEquals(
            "https://p123-photosupload.icloud.com:443",
            ICloudEndpoints.derivePhotosUploadBase("https://p123-ckdatabasews.icloud.com:443"),
        )
        assertEquals(
            "https://p55-photosupload.icloud.com.cn",
            ICloudEndpoints.resolvePhotosUploadBase("https://p55-ckdatabasews.icloud.com.cn", "https://p55-uploadphotosws.icloud.com.cn"),
        )
        assertEquals(
            "https://p9-photosupload.icloud.com",
            ICloudEndpoints.parsePhotosUploadRequest("https://p9-photosupload.icloud.com/photosupload/isRamped?dsid=1"),
        )
    }

    @Test
    fun sessionKnowsItsRegion() {
        val session = ICloudSession("X-APPLE-WEBAUTH-PCS-Photos=1", "https://p55-ckdatabasews.icloud.com.cn", "42")
        assertTrue(session.isChina)
        assertEquals("https://www.icloud.com.cn", session.homeEndpoint)
        assertTrue(session.isUsable())
        assertFalse(session.copy(cookieHeader = "a=b").isUsable())
        assertEquals("****1234", session.copy(dsid = "99991234").maskedDsid())
    }

    @Test
    fun queryParamsKeepApplesOrder() {
        assertEquals(
            "clientBuildNumber=B1&clientMasteringNumber=M1&clientId=abcdef01-2345-6789-abcd-ef0123456789&dsid=7&remapEnums=true&getCurrentSyncToken=true",
            ICloudEndpoints.buildQueryParams("B1", "M1", "ABCDEF01-2345-6789-ABCD-EF0123456789", "7"),
        )
        assertEquals(
            "clientBuildNumber=B1&clientMasteringNumber=M1&dsid=7&remapEnums=true&getCurrentSyncToken=true",
            ICloudEndpoints.buildQueryParams("B1", "M1", "not-a-uuid", "7"),
        )
    }

    private fun master(name: String, checksum: String, size: Long, filename: String? = null) = JSONObject()
        .put("recordName", name)
        .put("recordType", "CPLMaster")
        .put(
            "fields",
            JSONObject()
                .put("resOriginalRes", JSONObject().put("value", JSONObject().put("fileChecksum", checksum).put("size", size)))
                .put("resOriginalWidth", JSONObject().put("value", 4032))
                .put("resOriginalHeight", JSONObject().put("value", 3024))
                .apply {
                    if (filename != null) {
                        put(
                            "filenameEnc",
                            JSONObject().put("value", Base64.getEncoder().encodeToString(filename.toByteArray())).put("type", "ENCRYPTED_BYTES"),
                        )
                    }
                },
        )

    private fun asset(name: String, master: String, date: Long = 1_790_848_801_038L) = JSONObject()
        .put("recordName", name)
        .put("recordType", "CPLAsset")
        .put(
            "fields",
            JSONObject()
                .put("masterRef", JSONObject().put("value", JSONObject().put("recordName", master)))
                .put("assetDate", JSONObject().put("value", date))
                .put("timeZoneOffset", JSONObject().put("value", 28800)),
        )

    @Test
    fun pagePairsAssetsWithMasters() {
        val checksum = "AV6nbxjJYn7AL1szY2QnBcr9EtCf"
        val records = JSONArray().put(asset("A1", "M1")).put(master("M1", checksum, 1234, "IMG_0001.HEIC"))
        val page = RemoteRecords.parsePage(records, RemoteRecords.PRIMARY_ZONE, hidden = false)
        assertEquals(listOf("A1"), page.assetIds)
        val item = page.items.single()
        assertEquals(checksum, item.checksum)
        assertEquals("IMG_0001.HEIC", item.filename)
        assertEquals(1_790_848_801_038L, item.assetDate)
        assertEquals(28800, item.timeZoneOffsetSeconds)
        assertEquals(4032, item.width)
        assertEquals("M1", item.identity)
    }

    @Test
    fun brokenPairsRejectThePage() {
        val missingMaster = JSONArray().put(asset("A1", "M1"))
        val orphanMaster = JSONArray().put(master("M1", "AV6nbxjJYn7AL1szY2QnBcr9EtCf", 1))
        for (records in listOf(missingMaster, orphanMaster)) {
            try {
                RemoteRecords.parsePage(records, RemoteRecords.PRIMARY_ZONE, hidden = false)
                fail("expected rejection")
            } catch (expected: AppleProtocolException) {
            }
        }
    }

    @Test
    fun filenamesDecodeFromEveryForm() {
        val plain = JSONObject().put("filenameEnc", JSONObject().put("value", "IMG_1.JPG").put("type", "STRING"))
        assertEquals("IMG_1.JPG", RemoteRecords.filename(plain))
        val urlSafe = Base64.getUrlEncoder().withoutPadding().encodeToString("照片 1.heic".toByteArray())
        assertEquals("照片 1.heic", RemoteRecords.filename(JSONObject().put("filenameEnc", JSONObject().put("value", urlSafe))))
        val garbage = JSONObject().put("filenameEnc", JSONObject().put("value", "////").put("type", "ENCRYPTED_BYTES"))
        assertEquals("", RemoteRecords.filename(garbage))
    }

    @Test
    fun countsReadBothAnswerForms() {
        val batch = JSONObject().put(
            "batch",
            JSONArray().put(JSONObject().put("records", JSONArray().put(JSONObject().put("fields", JSONObject().put("itemCount", JSONObject().put("value", 42600)))))),
        )
        assertEquals(42600, RemoteRecords.parseCount(batch))
        val legacy = JSONObject().put(
            "records",
            JSONArray().put(JSONObject().put("recordType", "IndexCountResult").put("fields", JSONObject().put("itemCount", JSONObject().put("value", 7)))),
        )
        assertEquals(7, RemoteRecords.parseCount(legacy))
    }

    @Test
    fun zoneKeysRoundTrip() {
        assertEquals(PhotoZone.PRIMARY, PhotoZone.fromKey(PhotoZone.PRIMARY.key))
        val shared = PhotoZone("shared", "SharedSync-ABC|x", "_owner", "REGULAR_CUSTOM_ZONE")
        assertEquals(shared, PhotoZone.fromKey(shared.key))
        assertNull(PhotoZone.fromKey("5:abc"))
    }

    @Test
    fun httpFailuresMapToTheRightError() {
        assertThrows<SessionExpiredException> { AppleErrors.throwForHttpFailure("x", 401, "") }
        assertThrows<SessionExpiredException> { AppleErrors.throwForHttpFailure("x", 421, "") }
        assertThrows<WebAccessDeniedException> { AppleErrors.throwForHttpFailure("x", 403, "{\"reason\":\"ACCESS_DENIED\"}") }
        assertThrows<TransientAppleException> { AppleErrors.throwForHttpFailure("x", 503, "") }
        assertThrows<TransientAppleException> { AppleErrors.throwForHttpFailure("x", 429, "") }
        assertThrows<AppleProtocolException> { AppleErrors.throwForHttpFailure("x", 400, "") }
        assertEquals("", AppleErrors.serviceError(JSONObject().put("records", JSONArray())))
        assertTrue(AppleErrors.serviceError(JSONObject().put("serverErrorCode", "AUTHENTICATION_FAILED")).isNotEmpty())
    }

    @Test
    fun uploadAnswersAreValidated() {
        val url = PhotosUploader.parseCreateUploadUrl(
            JSONObject().put("uploadUrls", JSONObject().put("id1", "https://cvws.icloud-content.com/B/abc")).toString(), "id1",
        )
        assertEquals("https://cvws.icloud-content.com/B/abc", url)
        assertThrows<UploadException> {
            PhotosUploader.parseCreateUploadUrl(JSONObject().put("uploadUrls", JSONObject().put("id1", "https://evil.example.com/x")).toString(), "id1")
        }
        val signed = PhotosUploader.parseSignedUpload(
            JSONObject().put(
                "singleFile",
                JSONObject().put("fileChecksum", "AV6nbxjJYn7AL1szY2QnBcr9EtCf").put("referenceChecksum", "r").put("receipt", "rc").put("size", 10),
            ).toString(),
            10,
        )
        assertFalse(signed.duplicate)
        val wrongSize = assertThrows<UploadException> {
            PhotosUploader.parseSignedUpload(
                JSONObject().put("singleFile", JSONObject().put("fileChecksum", "c").put("referenceChecksum", "r").put("receipt", "rc").put("size", 9)).toString(),
                10,
            )
        }
        assertFalse(wrongSize.mayHaveBeenAccepted)
    }

    @Test
    fun putAssetAnswers() {
        val ok = PhotosUploader.parsePutAsset(JSONArray().put(JSONObject().put("uploadJobId", "J").put("cplMaster", "M").put("cplAsset", "A")).toString())
        assertEquals("J", ok.jobId)
        val duplicate = PhotosUploader.parsePutAsset(JSONArray().put(JSONObject().put("response", JSONObject().put("status", 409))).toString())
        assertTrue(duplicate.duplicateJob)
        assertThrows<SessionExpiredException> {
            PhotosUploader.parsePutAsset(JSONArray().put(JSONObject().put("response", JSONObject().put("status", 401))).toString())
        }
        val refusal = assertThrows<PhotosUploader.PutAssetRefusal> {
            PhotosUploader.parsePutAsset(JSONArray().put(JSONObject().put("response", JSONObject().put("status", 415).put("errorMessage", "Unsupported"))).toString())
        }
        assertTrue(refusal.explicitRefusal)
        val unclear = assertThrows<PhotosUploader.PutAssetRefusal> {
            PhotosUploader.parsePutAsset(JSONArray().put(JSONObject().put("response", JSONObject().put("status", 500).put("isRetryable", true))).toString())
        }
        assertFalse(unclear.explicitRefusal)
        val notArray = assertThrows<UploadException> { PhotosUploader.parsePutAsset("{}") }
        assertTrue(notArray.mayHaveBeenAccepted)
    }

    @Test
    fun jsonNullsAreEmptyText() {
        val json = JSONObject().put("uploadJobId", JSONObject.NULL).put("errorMessage", JSONObject.NULL).put("ok", " v ")
        assertEquals("", json.text("uploadJobId"))
        assertEquals("", json.text("missing"))
        assertEquals("v", json.text("ok"))
        val duplicate = PhotosUploader.parsePutAsset(
            JSONArray().put(
                JSONObject().put("uploadJobId", JSONObject.NULL).put("cplMaster", JSONObject.NULL)
                    .put("response", JSONObject().put("status", 409).put("errorMessage", JSONObject.NULL)),
            ).toString(),
        )
        assertTrue(duplicate.duplicateJob)
        val unclear = assertThrows<PhotosUploader.PutAssetRefusal> {
            PhotosUploader.parsePutAsset(
                JSONArray().put(JSONObject().put("uploadJobId", JSONObject.NULL).put("response", JSONObject().put("errorMessage", JSONObject.NULL))).toString(),
            )
        }
        assertFalse("a null message is no explicit refusal", unclear.explicitRefusal)
    }

    @Test
    fun uploadStatusAnswers() {
        assertEquals(100.0, PhotosUploader.parseUploadStatus(JSONObject().put("J", JSONObject().put("progress", 100).put("errorCode", JSONObject.NULL)).toString(), "J"), 0.0)
        assertEquals(40.0, PhotosUploader.parseUploadStatus(JSONObject().put("J", JSONObject().put("progress", 40).put("errorCode", 0)).toString(), "J"), 0.0)
        val rejected = assertThrows<UploadException> {
            PhotosUploader.parseUploadStatus(JSONObject().put("J", JSONObject().put("errorCode", 415)).toString(), "J")
        }
        assertTrue(rejected.itemRejected)
        assertFalse(rejected.mayHaveBeenAccepted)
        val busy = assertThrows<UploadException> {
            PhotosUploader.parseUploadStatus(JSONObject().put("J", JSONObject().put("errorCode", 503)).toString(), "J")
        }
        assertTrue(busy.mayHaveBeenAccepted)
    }

    @Test
    fun uploadRulesMatchAppleBehaviour() {
        assertTrue(PhotosUploader.endpointRejectsItem("createUploadUrl", 413))
        assertFalse(PhotosUploader.endpointRejectsItem("createUploadUrl", 400))
        assertTrue(PhotosUploader.endpointRejectsItem("putAsset", 400))
        assertFalse(PhotosUploader.isClientRejection(409))
        assertTrue(PhotosUploader.httpMayHaveBeenAccepted(502))
        assertFalse(PhotosUploader.httpMayHaveBeenAccepted(400))
        assertTrue(PhotosUploader.isValidImportGroup("0f8fad5b-d9cb-469f-a165-70867728950e"))
        assertFalse(PhotosUploader.isValidImportGroup("not-a-uuid"))
        assertEquals(-480, PhotosUploader.jsTimezoneOffsetMinutes(0, TimeZone.getTimeZone("Asia/Shanghai")))
        assertEquals(600L, PhotosUploader.transferTimeoutSeconds(1000))
        assertEquals(4 * 60 * 60L, PhotosUploader.transferTimeoutSeconds(10L * 1024 * 1024 * 1024))
    }

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw AssertionError("expected ${T::class.java.simpleName}, got $error", error)
        }
        throw AssertionError("expected ${T::class.java.simpleName}")
    }
}
