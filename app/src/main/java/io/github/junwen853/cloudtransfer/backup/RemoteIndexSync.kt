package io.github.junwen853.cloudtransfer.backup

import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.icloud.AppleProtocolException
import io.github.junwen853.cloudtransfer.icloud.CloudKitClient
import io.github.junwen853.cloudtransfer.icloud.PhotoZone
import io.github.junwen853.cloudtransfer.icloud.RemoteRecords
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import kotlin.coroutines.coroutineContext

/**
 * Keeps the local copy of the iCloud index complete: every original file in
 * iCloud Photos (personal library, hidden album and shared libraries) with its
 * Apple checksum. A local file is "already in iCloud" only if this index holds
 * its checksum, so the index must never silently miss a part of the library.
 */
class RemoteIndexSync(
    private val db: AppDatabase,
    private val client: CloudKitClient,
    private val dsid: String,
    private val progress: (Progress) -> Unit,
    private val log: (String) -> Unit = {},
) {
    class Summary(val total: Int, val fullRebuild: Boolean, val sharedZones: Int)

    private class Counts(val visible: Int, val hidden: Int) {
        val total get() = visible + hidden
    }

    companion object {
        private const val CONCURRENCY = 6
        private const val CHECKPOINT_EVERY = 2000
        private const val CHECKPOINT_MS = 60_000L
        private const val RECENT_OVERLAP = 500
        private const val FULL_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        private const val MAX_PAGES = 10_000

        const val KEY_DSID = "index.dsid"
        const val KEY_COMPLETE = "index.complete"
        const val KEY_FULL_AT = "index.fullAt"
        const val KEY_REFRESHED_AT = "index.refreshedAt"
        const val KEY_VISIBLE = "index.visible"
        const val KEY_HIDDEN = "index.hidden"
        const val KEY_SHARED = "index.sharedZones"
        private const val BUILD_DSID = "index.build.dsid"
        private const val BUILD_VISIBLE = "index.build.visible"
        private const val BUILD_HIDDEN = "index.build.hidden"
        private const val BUILD_NEXT = "index.build.next"
        private const val BUILD_LAST_IDS = "index.build.lastIds"
    }

    /** Brings the index up to date: a cheap refresh when possible, else a full read. */
    suspend fun sync(forceFull: Boolean = false): Summary {
        progress(Progress("读取 iCloud", "正在读取 iCloud 照片总数和共享图库。"))
        val counts = counts()
        val zones = client.sharedLibraryZones()
        val zoneSignature = zones.map { it.key }.sorted().joinToString("\n")
        val canRefresh = !forceFull && zones.isEmpty() &&
            db.meta(KEY_COMPLETE) == "1" && db.meta(KEY_DSID) == dsid &&
            db.meta(KEY_SHARED).orEmpty() == zoneSignature &&
            System.currentTimeMillis() - (db.meta(KEY_FULL_AT)?.toLongOrNull() ?: 0L) < FULL_MAX_AGE_MS
        if (canRefresh && refresh(counts)) {
            return Summary(db.remoteCount(), fullRebuild = false, sharedZones = 0)
        }
        var attempt = 1
        while (true) {
            val built = fullRebuild(if (attempt == 1) counts else counts(), zones)
            val after = counts()
            if (after.visible == built.visible && after.hidden == built.hidden) break
            // The library changed while it was read. New photos are picked up by a
            // refresh; anything else (a deletion) needs one more full read.
            if (zones.isEmpty() && refresh(after)) break
            if (attempt >= 2) {
                throw AppleProtocolException("iCloud 照片库在读取期间一直在变化，请稍后再检查。")
            }
            attempt++
        }
        return Summary(db.remoteCount(), fullRebuild = true, sharedZones = zones.size)
    }

    private suspend fun counts(): Counts = Counts(
        visible = client.count(CloudKitClient.COUNT_VISIBLE),
        hidden = client.count(CloudKitClient.COUNT_HIDDEN),
    )

    /**
     * Reads the most recently added photos (all new ones plus an overlap) and
     * the hidden album. Returns false when the result cannot prove the index
     * complete; the caller then reads everything.
     */
    private suspend fun refresh(counts: Counts): Boolean {
        val previousVisible = db.meta(KEY_VISIBLE)?.toIntOrNull() ?: return false
        val previousHidden = db.meta(KEY_HIDDEN)?.toIntOrNull() ?: return false
        val expectedGrowth = counts.total - (previousVisible + previousHidden)
        if (counts.visible < previousVisible || expectedGrowth < 0) {
            log("iCloud 照片数从 ${previousVisible + previousHidden} 变为 ${counts.total}（有删除或隐藏），改为完整读取。")
            return false
        }
        val growth = counts.visible - previousVisible
        val target = minOf(counts.visible, maxOf(RECENT_OVERLAP, growth + RECENT_OVERLAP))
        // Rank 0 of the added-date list is the most recently added photo, so
        // reading from 0 covers everything added since last time.
        var rank = 0
        var inspected = 0
        var added = 0
        var pages = 0
        progress(Progress("更新 iCloud 索引", "iCloud 现有 ${counts.total} 张；正在检查最近加入的 $target 张。", 0, target.toLong()))
        while (inspected < target && pages < MAX_PAGES) {
            coroutineContext.ensureActive()
            val records = client.fetchPage(rank, CloudKitClient.LIST_RECENT)
            val page = RemoteRecords.parsePage(records, RemoteRecords.PRIMARY_ZONE, hidden = false)
            if (page.assetIds.isEmpty()) break
            val new = db.upsertRemote(AppDatabase.REMOTE, page.items)
            added += new
            android.util.Log.d("CloudTransfer", "refresh rank=$rank assets=${page.assetIds.size} new=$new first=${page.items.firstOrNull()?.filename}")
            inspected += page.assetIds.size
            rank += page.assetIds.size
            pages++
            progress(
                Progress(
                    "更新 iCloud 索引", "已检查最近加入的 ${minOf(inspected, target)}/$target 张。",
                    minOf(inspected, target).toLong(), target.toLong(),
                ),
            )
        }
        if (inspected < target) {
            log("增量更新没读够最近加入的照片（$inspected/$target），改为完整读取。")
            return false
        }
        if (counts.hidden > 0 || previousHidden > 0) {
            added += readZoneCategory(PhotoZone.PRIMARY, CloudKitClient.LIST_HIDDEN, hidden = true, AppDatabase.REMOTE, counts.hidden)
        }
        if (added != expectedGrowth) {
            log("增量更新发现 $added 个新文件，但 iCloud 总数变化了 $expectedGrowth，可能有删除；改为完整读取。")
            return false
        }
        log("增量更新：检查了最近加入的 $inspected 张，新增 $added 个。")
        db.setMeta(KEY_VISIBLE, counts.visible.toString())
        db.setMeta(KEY_HIDDEN, counts.hidden.toString())
        db.setMeta(KEY_REFRESHED_AT, System.currentTimeMillis().toString())
        return true
    }

    private suspend fun fullRebuild(counts: Counts, zones: List<PhotoZone>): Counts {
        var rank = resumeRank(counts, zones)
        if (rank == 0) {
            db.resetBuildTable()
            db.setMeta(BUILD_DSID, dsid)
            db.setMeta(BUILD_VISIBLE, counts.visible.toString())
            db.setMeta(BUILD_HIDDEN, counts.hidden.toString())
            db.setMeta(BUILD_NEXT, "0")
        } else {
            progress(Progress("读取 iCloud", "从上次中断处继续：已读 $rank/${counts.visible} 张。", rank.toLong(), counts.total.toLong()))
        }
        var lastCheckpointRank = rank
        var lastCheckpointAt = System.currentTimeMillis()
        var pages = 0
        progress(Progress("读取 iCloud", "正在读取 iCloud 全部 ${counts.total} 张照片的内容指纹。", rank.toLong(), counts.total.toLong()))
        while (rank < counts.visible && pages < MAX_PAGES) {
            coroutineContext.ensureActive()
            val starts = (0 until CONCURRENCY)
                .map { rank + it * CloudKitClient.PAGE_PHOTOS }
                .filter { it < counts.visible }
            val results: List<JSONArray> = coroutineScope {
                starts.map { start -> async { client.fetchPage(start, CloudKitClient.LIST_VISIBLE) } }.awaitAll()
            }
            var lastIds: List<String> = emptyList()
            for ((index, records) in results.withIndex()) {
                val start = starts[index]
                // A short page leaves a gap before the next page of this batch;
                // the loop continues from where the data really ends.
                if (start != rank) break
                val page = RemoteRecords.parsePage(records, RemoteRecords.PRIMARY_ZONE, hidden = false)
                if (page.assetIds.isEmpty()) {
                    throw AppleProtocolException("iCloud 在第 $rank/${counts.visible} 张处提前结束，照片库可能正在变化，请稍后重试。")
                }
                db.upsertRemote(AppDatabase.REMOTE_BUILD, page.items)
                rank += page.assetIds.size
                lastIds = page.assetIds
                pages++
            }
            progress(
                Progress(
                    "读取 iCloud", "已读取 ${minOf(rank, counts.visible)}/${counts.total} 张照片的内容指纹。",
                    minOf(rank, counts.visible).toLong(), counts.total.toLong(),
                ),
            )
            val now = System.currentTimeMillis()
            if (zones.isEmpty() && (rank - lastCheckpointRank >= CHECKPOINT_EVERY || now - lastCheckpointAt >= CHECKPOINT_MS)) {
                db.setMeta(BUILD_NEXT, rank.toString())
                db.setMeta(BUILD_LAST_IDS, JSONArray(lastIds).toString())
                lastCheckpointRank = rank
                lastCheckpointAt = now
            }
        }
        if (rank < counts.visible) {
            throw AppleProtocolException("iCloud 照片索引超过 $MAX_PAGES 页仍未读完。")
        }
        if (counts.hidden > 0) {
            progress(Progress("读取 iCloud", "正在读取隐藏相簿的 ${counts.hidden} 张。", counts.visible.toLong(), counts.total.toLong()))
            readZoneCategory(PhotoZone.PRIMARY, CloudKitClient.LIST_HIDDEN, hidden = true, AppDatabase.REMOTE_BUILD, counts.hidden)
        }
        for ((index, zone) in zones.withIndex()) {
            progress(Progress("读取共享图库", "正在读取共享图库 ${index + 1}/${zones.size}。"))
            readZoneCategory(zone, CloudKitClient.LIST_VISIBLE, hidden = false, AppDatabase.REMOTE_BUILD, expected = -1)
            readZoneCategory(zone, CloudKitClient.LIST_HIDDEN, hidden = true, AppDatabase.REMOTE_BUILD, expected = -1)
        }
        db.promoteBuildTable()
        val now = System.currentTimeMillis().toString()
        db.setMeta(KEY_DSID, dsid)
        db.setMeta(KEY_VISIBLE, counts.visible.toString())
        db.setMeta(KEY_HIDDEN, counts.hidden.toString())
        db.setMeta(KEY_SHARED, zones.map { it.key }.sorted().joinToString("\n"))
        db.setMeta(KEY_FULL_AT, now)
        db.setMeta(KEY_REFRESHED_AT, now)
        db.setMeta(KEY_COMPLETE, "1")
        listOf(BUILD_DSID, BUILD_VISIBLE, BUILD_HIDDEN, BUILD_NEXT, BUILD_LAST_IDS).forEach { db.setMeta(it, null) }
        return counts
    }

    /**
     * Where an interrupted full read can continue, or 0. Continuing is allowed
     * only for the same account and unchanged counts, and only if the last
     * saved page still holds the same photos at the same position.
     */
    private suspend fun resumeRank(counts: Counts, zones: List<PhotoZone>): Int {
        if (zones.isNotEmpty()) return 0
        if (db.meta(BUILD_DSID) != dsid) return 0
        if (db.meta(BUILD_VISIBLE)?.toIntOrNull() != counts.visible) return 0
        if (db.meta(BUILD_HIDDEN)?.toIntOrNull() != counts.hidden) return 0
        val next = db.meta(BUILD_NEXT)?.toIntOrNull() ?: return 0
        if (next <= 0 || next > counts.visible) return 0
        val lastIds = runCatching {
            val array = JSONArray(db.meta(BUILD_LAST_IDS).orEmpty())
            List(array.length()) { array.getString(it) }
        }.getOrNull() ?: return 0
        if (lastIds.isEmpty() || lastIds.size > next) return 0
        return try {
            val records = client.fetchPage(next - lastIds.size, CloudKitClient.LIST_VISIBLE, photos = lastIds.size)
            val page = RemoteRecords.parsePage(records, RemoteRecords.PRIMARY_ZONE, hidden = false)
            if (page.assetIds == lastIds && db.remoteCount(AppDatabase.REMOTE_BUILD) <= next) next else 0
        } catch (_: AppleProtocolException) {
            0
        }
    }

    /**
     * Reads one record list of a zone from the start. [expected] is the count
     * Apple reported, or -1 to read until Apple's last page.
     */
    private suspend fun readZoneCategory(
        zone: PhotoZone,
        recordType: String,
        hidden: Boolean,
        table: String,
        expected: Int,
    ): Int {
        var rank = 0
        var added = 0
        var pages = 0
        while ((expected < 0 || rank < expected) && pages < MAX_PAGES) {
            coroutineContext.ensureActive()
            val records = client.fetchPage(rank, recordType, zone)
            val page = RemoteRecords.parsePage(records, zone.key, hidden)
            if (page.assetIds.isEmpty()) {
                if (expected >= 0) {
                    throw AppleProtocolException("iCloud 隐藏相簿在第 $rank/$expected 张处提前结束，请稍后重试。")
                }
                break
            }
            added += db.upsertRemote(table, page.items)
            rank += page.assetIds.size
            pages++
            if (expected < 0 && page.assetIds.size < CloudKitClient.PAGE_PHOTOS) break
        }
        return added
    }
}
