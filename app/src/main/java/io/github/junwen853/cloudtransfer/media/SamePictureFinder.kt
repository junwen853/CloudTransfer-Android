package io.github.junwen853.cloudtransfer.media

import io.github.junwen853.cloudtransfer.icloud.text
import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.icloud.AppleHttp
import io.github.junwen853.cloudtransfer.icloud.CloudKitClient
import io.github.junwen853.cloudtransfer.icloud.PhotoZone
import io.github.junwen853.cloudtransfer.icloud.RemoteItem
import io.github.junwen853.cloudtransfer.icloud.SessionExpiredException
import io.github.junwen853.cloudtransfer.icloud.WebAccessDeniedException
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Finds photos that are in iCloud as a different file: renamed, location or
 * other metadata removed, or re-saved. Candidates must have been taken at the
 * same moment (to the second) with the same shape; the decision is made by
 * comparing the pictures themselves against iCloud's thumbnail.
 */
class SamePictureFinder(
    private val db: AppDatabase,
    private val cloudKit: CloudKitClient,
    private val http: AppleHttp,
    private val store: LocalMediaStore,
    private val report: (String) -> Unit,
) {
    companion object {
        private const val TIME_TOLERANCE_MS = 1500L
        private const val MAX_ZONE_OFFSET_MS = 14L * 60 * 60 * 1000
        private const val MAX_CANDIDATES = 5
        private const val MAX_THUMBNAIL_BYTES = 8 * 1024 * 1024
        private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
        private val VIDEO_EXTENSIONS = setOf("mov", "mp4", "m4v")

        fun parseExifTime(value: String): LocalDateTime? =
            runCatching { LocalDateTime.parse(value.trim().take(19), EXIF_TIME) }.getOrNull()

        /**
         * The same shot: to the millisecond when both times carry milliseconds
         * (burst frames are tens of milliseconds apart), else within the second.
         */
        fun sameMoment(remoteMs: Long, localMs: Long): Boolean {
            val difference = abs(remoteMs - localMs)
            if (difference <= 10) return true
            val secondsOnly = Math.floorMod(remoteMs, 1000L) == 0L || Math.floorMod(localMs, 1000L) == 0L
            return secondsOnly && difference < 1000 && Math.floorDiv(remoteMs, 1000L) == Math.floorDiv(localMs, 1000L)
        }

        /** Shapes match when the long/short side ratios agree within 2%. */
        fun sameShape(width: Int, height: Int, otherWidth: Int, otherHeight: Int): Boolean {
            if (width <= 0 || height <= 0 || otherWidth <= 0 || otherHeight <= 0) return true
            val ratio = maxOf(width, height).toDouble() / minOf(width, height)
            val otherRatio = maxOf(otherWidth, otherHeight).toDouble() / minOf(otherWidth, otherHeight)
            return abs(ratio - otherRatio) / otherRatio < 0.02
        }

        /**
         * Wall-clock capture times iCloud's record may stand for. The unit and sign
         * of CPLAsset.timeZoneOffset are not documented, so every reading is tried;
         * the picture comparison decides.
         */
        fun remoteWallTimes(item: RemoteItem): List<Long> {
            val offset = item.timeZoneOffsetSeconds ?: return emptyList()
            val seconds = if (abs(offset) <= 900) offset * 60L else offset.toLong()
            return listOf(item.assetDate + seconds * 1000, item.assetDate - seconds * 1000).distinct()
        }
    }

    private class Pairing(
        val media: LocalMedia,
        val remote: RemoteItem,
        val timeDifference: Long,
        val correlation: Double,
        /** The remote photo is also some phone file exactly; this is a further copy of it. */
        val copyOfMatched: Boolean,
    )

    /**
     * mediaId -> explanation, for every file whose picture iCloud already has.
     *
     * Matching is one-to-one: an iCloud photo stands for at most one phone file,
     * the closest in time, and never for anything once another phone file is
     * that exact photo. So frames of a burst, which look almost alike, are not
     * all taken for the one frame that is in iCloud.
     */
    suspend fun find(items: List<Pair<LocalMedia, String>>, localChecksums: Set<String>): Map<Long, String> {
        val pairings = ArrayList<Pairing>()
        for ((index, entry) in items.withIndex()) {
            coroutineContext.ensureActive()
            val (media, checksum) = entry
            report("正在比对第 ${index + 1}/${items.size} 个的拍摄时间和画面：${media.displayName}")
            val candidates = candidatesFor(media, localChecksums)
            if (candidates.isEmpty()) continue
            val local = localGrid(media) ?: continue
            for ((candidate, difference) in candidates) {
                val remote = remoteGrid(candidate) ?: continue
                val correlation = VisualHash.bestCorrelation(local, remote) ?: continue
                val copyOfMatched = candidate.checksum in localChecksums
                // A further copy of a photo that is on the phone already must look
                // identical; burst frames differ in the picture.
                val minimum = if (copyOfMatched) VisualHash.COPY_MIN_CORRELATION else VisualHash.SAME_PICTURE_MIN_CORRELATION
                if (correlation >= minimum) pairings += Pairing(media, candidate, difference, correlation, copyOfMatched)
            }
        }
        val matches = HashMap<Long, String>()
        val usedRemote = HashSet<String>()
        for (pairing in pairings.sortedWith(compareBy({ it.timeDifference }, { -it.correlation }))) {
            if (pairing.media.id in matches) continue
            if (!pairing.copyOfMatched) {
                if (pairing.remote.identity in usedRemote) continue
                usedRemote += pairing.remote.identity
            }
            val name = pairing.remote.filename.ifEmpty { "无文件名的照片" }
            matches[pairing.media.id] = "iCloud 里已有同一张照片「$name」（拍摄时间相同、画面一致，只是文件不同）"
        }
        return matches
    }

    private fun candidatesFor(media: LocalMedia, localChecksums: Set<String>): List<Pair<RemoteItem, Long>> {
        val byIdentity = LinkedHashMap<String, Pair<RemoteItem, Long>>()
        fun consider(item: RemoteItem, difference: Long) {
            if (MediaFormats.extension(item.filename) in VIDEO_EXTENSIONS) return
            if (!sameShape(media.width, media.height, item.width, item.height)) return
            val previous = byIdentity[item.identity]
            if (previous == null || difference < previous.second) byIdentity[item.identity] = item to difference
        }
        if (media.dateTaken > 0) {
            for (item in db.remoteByCaptureWindow(media.dateTaken - TIME_TOLERANCE_MS, media.dateTaken + TIME_TOLERANCE_MS)) {
                if (sameMoment(item.assetDate, media.dateTaken)) consider(item, abs(item.assetDate - media.dateTaken))
            }
        }
        val wall = exifTime(media)?.let { parseExifTime(it) }
        if (wall != null) {
            val inDeviceZone = wall.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            for (item in db.remoteByCaptureWindow(inDeviceZone - TIME_TOLERANCE_MS, inDeviceZone + TIME_TOLERANCE_MS)) {
                if (sameMoment(item.assetDate, inDeviceZone)) consider(item, abs(item.assetDate - inDeviceZone))
            }
            val wallAsUtc = wall.toInstant(ZoneOffset.UTC).toEpochMilli()
            for (item in db.remoteByCaptureWindow(wallAsUtc - MAX_ZONE_OFFSET_MS, wallAsUtc + MAX_ZONE_OFFSET_MS)) {
                remoteWallTimes(item).filter { sameMoment(it, wallAsUtc) }.map { abs(it - wallAsUtc) }.minOrNull()
                    ?.let { consider(item, it) }
            }
        }
        return byIdentity.values.sortedBy { it.second }.take(MAX_CANDIDATES)
    }

    private fun exifTime(media: LocalMedia): String? {
        val cached = db.localHash(media.id, media.size, media.dateModified)
        if (cached != null && cached.exifTime.isNotEmpty()) return cached.exifTime.takeIf { it != "-" }
        val value = store.exifCaptureTime(media)
        if (cached != null) db.saveLocalVisual(media.id, cached.visual, value.ifEmpty { "-" })
        return value.ifEmpty { null }
    }

    private fun localGrid(media: LocalMedia): DoubleArray? {
        val cached = db.localHash(media.id, media.size, media.dateModified)
        cached?.visual?.let { VisualHash.decode(it) }?.let { return it }
        val grid = try {
            VisualHash.gridOf { store.openStream(media) }
        } catch (_: Exception) {
            null
        } ?: return null
        if (cached != null) db.saveLocalVisual(media.id, VisualHash.encode(grid), cached.exifTime)
        return grid
    }

    private suspend fun remoteGrid(item: RemoteItem): DoubleArray? {
        db.remoteVisual(item.identity)?.let { VisualHash.decode(it) }?.let { return it }
        val zone = PhotoZone.fromKey(item.zone) ?: return null
        return try {
            val records = cloudKit.lookup(listOf(item.recordName), zone, listOf("resJPEGThumbRes"))
            val url = records.optJSONObject(0)?.optJSONObject("fields")?.optJSONObject("resJPEGThumbRes")
                ?.optJSONObject("value").text("downloadURL")
            if (url.isEmpty()) return null
            val bytes = http.download(url.replace("\${f}", "thumbnail.jpg"), MAX_THUMBNAIL_BYTES)
            val grid = VisualHash.gridOf { ByteArrayInputStream(bytes) } ?: return null
            grid.also { db.saveRemoteVisual(item.identity, VisualHash.encode(it)) }
        } catch (error: SessionExpiredException) {
            throw error
        } catch (error: WebAccessDeniedException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
