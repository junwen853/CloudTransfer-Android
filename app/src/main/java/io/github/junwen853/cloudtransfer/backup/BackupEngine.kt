package io.github.junwen853.cloudtransfer.backup

import io.github.junwen853.cloudtransfer.icloud.text
import android.content.Context
import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.data.PendingStage
import io.github.junwen853.cloudtransfer.data.PendingUpload
import io.github.junwen853.cloudtransfer.data.PlanCategory
import io.github.junwen853.cloudtransfer.data.PlanRow
import io.github.junwen853.cloudtransfer.data.SessionStore
import io.github.junwen853.cloudtransfer.data.Settings
import io.github.junwen853.cloudtransfer.icloud.AppleChecksum
import io.github.junwen853.cloudtransfer.icloud.AppleHttp
import io.github.junwen853.cloudtransfer.icloud.AppleProtocolException
import io.github.junwen853.cloudtransfer.icloud.CloudKitClient
import io.github.junwen853.cloudtransfer.icloud.PhotoZone
import io.github.junwen853.cloudtransfer.icloud.PhotosUploader
import io.github.junwen853.cloudtransfer.icloud.SessionExpiredException
import io.github.junwen853.cloudtransfer.icloud.TransientAppleException
import io.github.junwen853.cloudtransfer.icloud.UploadException
import io.github.junwen853.cloudtransfer.icloud.VerifyingFileBody
import io.github.junwen853.cloudtransfer.icloud.WebAccessDeniedException
import io.github.junwen853.cloudtransfer.media.LocalMedia
import io.github.junwen853.cloudtransfer.media.LocalMediaStore
import io.github.junwen853.cloudtransfer.media.LocalReadException
import io.github.junwen853.cloudtransfer.media.MediaFormats
import io.github.junwen853.cloudtransfer.media.SamePictureFinder
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Capture-time range of a check, in UTC milliseconds (inclusive). */
data class DateRange(val from: Long, val to: Long, val label: String)

/**
 * The backup itself: bring the iCloud index up to date, compare every gallery
 * file with it, and upload what is missing. A file counts as backed up only
 * after iCloud itself shows it.
 */
class BackupEngine(context: Context, private val mode: RunMode) {
    private val appContext = context.applicationContext
    private val db = AppDatabase.get(appContext)
    private val sessions = SessionStore.get(appContext)
    private val settings = Settings(appContext)
    private val store = LocalMediaStore(appContext)
    private val http = AppleHttp(sessions)
    private val cloudKit = CloudKitClient(http, sessions)
    private val uploader = PhotosUploader(http, sessions)

    companion object {
        /** An unconfirmed upload that iCloud still does not show after this long is sent again. */
        const val PENDING_RELEASE_MS = 24L * 60 * 60 * 1000
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    class Candidate(val media: LocalMedia, val checksum: String, val size: Long)

    class CheckOutcome(val summary: CheckSummary, val toUpload: List<Candidate>)

    private enum class Result { UPLOADED, ALREADY_IN_ICLOUD, AWAITING }

    private fun progress(progress: Progress) = BackupState.progress(mode, progress)

    private fun log(line: String) = BackupState.log(line)

    private fun requireReadyToRead() {
        if (!LocalMediaStore.hasFullReadAccess(appContext) && !LocalMediaStore.hasPartialReadAccess(appContext)) {
            throw LocalReadException("没有读取照片和视频的权限，请先授权。")
        }
        if (!LocalMediaStore.hasLocationAccess(appContext)) {
            throw LocalReadException("没有“访问照片位置信息”权限。没有它，系统会把照片的拍摄地点去掉，上传的就不是原图了。请先授权。")
        }
    }

    // ---------------------------------------------------------------- check

    suspend fun check(range: DateRange?, forceFullIndex: Boolean = false): CheckOutcome {
        val session = sessions.load() ?: throw SessionExpiredException("还没有登录 iCloud。")
        requireReadyToRead()
        if (LocalMediaStore.hasPartialReadAccess(appContext)) {
            log("注意：只允许了访问部分照片，本次只检查你选中的那些。")
        }

        val sync = RemoteIndexSync(db, cloudKit, session.dsid, ::progress, ::log).sync(forceFullIndex)
        log(
            "iCloud 现有 ${sync.total} 个原始文件" + (if (sync.fullRebuild) "（完整读取）" else "（增量更新）") +
                (if (sync.sharedZones > 0) "，含 ${sync.sharedZones} 个共享图库" else "") + "。",
        )
        resolvePending(session.dsid)

        progress(Progress("读取相册", "正在列出手机里的照片和视频。"))
        val media = store.list(range?.from ?: Long.MIN_VALUE, range?.to ?: Long.MAX_VALUE)
        log("手机里${range?.label?.let { "（$it）" } ?: ""}共有 ${media.size} 个照片和视频。")

        val remote = db.remoteChecksums()
        val uploaded = db.uploadedRows(session.dsid)
        val refreshedAt = db.meta(RemoteIndexSync.KEY_REFRESHED_AT)?.toLongOrNull() ?: 0L
        val forced = db.forcedChecksums()
        val pending = db.pendingAll().filter { it.dsid == session.dsid }.associateBy { it.checksum }
        val rejected = db.rejectedReasons()
        val seen = HashMap<String, String>()
        val rows = ArrayList<PlanRow>(media.size)
        val candidates = ArrayList<Candidate>()
        var hashedBytes = 0L
        val totalBytes = media.sumOf { it.size }

        for ((index, item) in media.withIndex()) {
            coroutineContext.ensureActive()
            progress(
                Progress(
                    "计算内容指纹", "正在核对第 ${index + 1}/${media.size} 个：${item.displayName}",
                    hashedBytes, totalBytes.coerceAtLeast(1),
                ),
            )
            hashedBytes += item.size
            val block = MediaFormats.blockReason(item.displayName, item.mimeType, item.size, item.isVideo)
            if (block.isNotEmpty()) {
                rows += row(item, "", PlanCategory.BLOCKED, block)
                continue
            }
            val hashed = try {
                checksumOf(item)
            } catch (error: LocalReadException) {
                rows += row(item, "", PlanCategory.UNREADABLE, error.message.orEmpty())
                continue
            }
            val checksum = hashed.first
            val (category, detail) = when {
                checksum in remote -> PlanCategory.IN_ICLOUD to "iCloud 里有完全相同的文件"
                seen.containsKey(checksum) -> PlanCategory.LOCAL_DUPLICATE to "和相册里的「${seen[checksum]}」是同一个文件，只算一次"
                pending.containsKey(checksum) -> PlanCategory.AWAITING_CONFIRMATION to "之前已经上传，正在等 iCloud 确认，不会重复上传"
                uploaded[checksum]?.let { it.uploadedAt > refreshedAt } == true ->
                    PlanCategory.IN_ICLOUD to "刚由本 App 上传并已确认"
                uploaded.containsKey(checksum) && checksum !in forced ->
                    PlanCategory.DELETED_IN_ICLOUD to "以前由本 App 上传过，但现在 iCloud 里没有了（可能在 iCloud 删除了）"
                rejected.containsKey(checksum) && checksum !in forced ->
                    PlanCategory.FAILED to "上次被 Apple 拒收：${rejected[checksum]}"
                else -> PlanCategory.TO_UPLOAD to ""
            }
            seen.putIfAbsent(checksum, item.displayName)
            rows += row(item, checksum, category, detail)
            if (category == PlanCategory.TO_UPLOAD) candidates += Candidate(item, checksum, hashed.second)
        }

        var toUpload: List<Candidate> = candidates
        if (settings.samePictureCheck && candidates.isNotEmpty()) {
            val finder = SamePictureFinder(db, cloudKit, http, store) { message ->
                progress(Progress("比对画面", message))
            }
            val matches = finder.find(
                candidates.filter { !it.media.isVideo && it.checksum !in forced }.map { it.media to it.checksum },
                localChecksums = seen.keys,
            )
            if (matches.isNotEmpty()) {
                toUpload = candidates.filter { it.media.id !in matches }
                for ((position, current) in rows.withIndex()) {
                    val match = matches[current.mediaId] ?: continue
                    rows[position] = current.copy(category = PlanCategory.SAME_PICTURE_IN_ICLOUD, detail = match)
                }
                log("按画面比对，另有 ${matches.size} 个在 iCloud 里已有同一张照片（文件名或元数据不同）。")
            }
        }

        db.replacePlan(rows)
        val summary = summarize(rows, range, db.remoteCount())
        BackupState.publish(summary)
        return CheckOutcome(summary, toUpload)
    }

    private fun row(item: LocalMedia, checksum: String, category: PlanCategory, detail: String) = PlanRow(
        mediaId = item.id,
        isVideo = item.isVideo,
        displayName = item.displayName,
        size = item.size,
        dateTaken = item.captureTime,
        checksum = checksum,
        category = category,
        detail = detail,
    )

    /** Checksum and true byte count, from the cache when the file did not change. */
    private suspend fun checksumOf(item: LocalMedia): Pair<String, Long> {
        db.localHash(item.id, item.size, item.dateModified)?.let { return it.checksum to item.size }
        val context = coroutineContext
        val result = store.checksum(item) { context.ensureActive() }
        if (result.size != item.size) {
            // The gallery and the file disagree: it is being written or was just edited.
            throw LocalReadException("文件实际大小和相册记录不一致（可能正在保存或刚被编辑），下次再检查。")
        }
        db.saveLocalHash(item.id, item.size, item.dateModified, result.checksum)
        return result.checksum to result.size
    }

    private fun summarize(rows: List<PlanRow>, range: DateRange?, iCloudTotal: Int): CheckSummary {
        val finishedAt = System.currentTimeMillis()
        db.setMeta("plan.range", range?.label ?: "")
        db.setMeta("plan.from", range?.from?.toString())
        db.setMeta("plan.to", range?.to?.toString())
        db.setMeta("plan.at", finishedAt.toString())
        return summarizePlan(rows, range, iCloudTotal, finishedAt)
    }

    // --------------------------------------------------------------- upload

    /**
     * Checks, then uploads what is missing. With a [deadline] (a background run
     * that Android may stop), files that would not finish in time wait for a
     * later run instead of being cut off again and again.
     */
    suspend fun upload(range: DateRange?, deadline: Long = 0L): CheckSummary {
        val outcome = check(range)
        val session = sessions.current()
        val items = outcome.toUpload
        if (items.isEmpty()) {
            log("没有需要上传的照片或视频。")
            return outcome.summary
        }
        val totalBytes = items.sumOf { it.size }
        log("开始上传 ${items.size} 个，共 ${formatBytes(totalBytes)}。")
        var done = 0
        var doneBytes = 0L
        var consecutiveFailures = 0
        var deferred = 0
        for (candidate in items) {
            coroutineContext.ensureActive()
            val label = "${done + 1}/${items.size}"
            if (deadline > 0 && System.currentTimeMillis() + estimatedUploadMs(candidate.size) > deadline) {
                deferred++
                db.updatePlanCategory(candidate.media.id, PlanCategory.TO_UPLOAD, "这次后台运行时间不够，留到下次或手动上传")
                done++
                doneBytes += candidate.size
                continue
            }
            val (category, detail) = try {
                when (uploadOne(candidate, session.dsid, label, doneBytes, totalBytes)) {
                    Result.UPLOADED -> PlanCategory.UPLOADED to "已上传，iCloud 已确认"
                    Result.ALREADY_IN_ICLOUD -> PlanCategory.IN_ICLOUD to "Apple 确认 iCloud 里已有相同文件"
                    Result.AWAITING -> PlanCategory.AWAITING_CONFIRMATION to "已交给 Apple，正在等 iCloud 显示；不会重复上传"
                }.also { consecutiveFailures = 0 }
            } catch (error: UploadException) {
                // Only failures that say nothing about this one file (network,
                // unclear answers) count towards stopping the batch.
                if (error.itemRejected || error.localFailure) {
                    consecutiveFailures = 0
                    if (error.itemRejected) db.setRejected(candidate.checksum, error.message.orEmpty())
                    if (error.localFailure) db.deleteLocalHash(candidate.media.id)
                } else {
                    consecutiveFailures++
                }
                log("${candidate.media.displayName}：${error.message}")
                if (error.mayHaveBeenAccepted) {
                    PlanCategory.AWAITING_CONFIRMATION to "结果不确定，先不重传：${error.message}"
                } else {
                    PlanCategory.FAILED to error.message.orEmpty()
                }
            } catch (error: LocalReadException) {
                consecutiveFailures = 0
                log("${candidate.media.displayName}：${error.message}")
                PlanCategory.FAILED to error.message.orEmpty()
            }
            db.updatePlanCategory(candidate.media.id, category, detail)
            BackupState.planChanged()
            done++
            doneBytes += candidate.size
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                log("连续 $MAX_CONSECUTIVE_FAILURES 个上传失败，先停下。请检查网络后再试。")
                break
            }
        }
        val rows = db.planItems()
        val summary = summarize(rows, range, db.remoteCount())
        BackupState.publish(summary)
        log("上传结束：成功 ${summary.uploaded} 个，失败 ${summary.failed} 个，等待确认 ${summary.awaiting} 个。")
        if (deferred > 0) log("有 $deferred 个文件较大，这次后台运行时间不够，留到下次。")
        return summary
    }

    private suspend fun uploadOne(candidate: Candidate, dsid: String, label: String, doneBytes: Long, totalBytes: Long): Result {
        val media = candidate.media
        val name = media.displayName
        if (db.pending(candidate.checksum) != null) return Result.AWAITING
        if (db.isUploaded(candidate.checksum, dsid) && candidate.checksum !in db.forcedChecksums()) return Result.ALREADY_IN_ICLOUD
        val base = PendingUpload(
            checksum = candidate.checksum,
            mediaId = media.id,
            displayName = name,
            size = candidate.size,
            captureTime = media.captureTime,
            dsid = dsid,
            stage = PendingStage.SENDING,
        )
        // The lock is written before any byte leaves the phone.
        db.savePending(base)
        val received: JSONObject
        try {
            var signed: io.github.junwen853.cloudtransfer.icloud.SignedUploadResult? = null
            var body: VerifyingFileBody? = null
            for (attempt in 1..3) {
                progress(Progress("上传 $label", "正在为 $name 申请上传通道。", doneBytes, totalBytes))
                val url = uploader.createUploadUrl(candidate.size)
                body = VerifyingFileBody({ store.openStream(media) }, candidate.size) { sent, _ ->
                    progress(Progress("上传 $label", "正在上传 $name（${formatBytes(sent)}/${formatBytes(candidate.size)}）", doneBytes + sent, totalBytes))
                }
                try {
                    signed = uploader.sendFile(url, body, name)
                    break
                } catch (error: UploadException) {
                    if (!error.retryableTransfer || attempt >= 3) throw error
                    log("$name 网络中断，第 ${attempt + 1} 次重新上传。")
                    delay(2000L * attempt)
                }
            }
            val result = signed ?: throw UploadException("$name 上传失败。", mayHaveBeenAccepted = false)
            if (body?.sentChecksum != candidate.checksum) {
                throw UploadException("$name 的内容和检查时不一致（可能刚被编辑），没有加入照片库，请重新检查。", mayHaveBeenAccepted = false, localFailure = true)
            }
            if (result.duplicate) {
                db.savePending(base.copy(stage = PendingStage.DUPLICATE_JOB, lastError = "Apple 表示已有相同内容"))
                return if (confirmIfInICloud(base)) Result.ALREADY_IN_ICLOUD else Result.AWAITING
            }
            received = result.singleFile ?: throw UploadException("Apple 上传回执缺少 singleFile。", mayHaveBeenAccepted = false)
            if (AppleChecksum.normalize(received.text("fileChecksum")) != candidate.checksum) {
                throw UploadException("Apple 收到的文件指纹和本机不一致，没有加入照片库。", mayHaveBeenAccepted = false)
            }
        } catch (error: Exception) {
            // Before putAsset nothing can be in the library: the lock can go.
            db.deletePending(candidate.checksum)
            throw error
        }
        val receivedRow = base.copy(
            stage = PendingStage.FILE_RECEIVED,
            importGroup = UUID.randomUUID().toString().lowercase(),
            singleFileJson = received.toString(),
        )
        db.savePending(receivedRow)
        progress(Progress("上传 $label", "$name 已传完，正在加入照片库。", doneBytes + candidate.size, totalBytes))
        return submit(receivedRow, received)
    }

    /** putAsset, then wait for Apple's job and confirm the result in iCloud. */
    private suspend fun submit(row: PendingUpload, singleFile: JSONObject): Result {
        val put = try {
            uploader.putAsset(row.displayName, row.captureTime, singleFile, row.importGroup)
        } catch (error: UploadException) {
            if (error.mayHaveBeenAccepted) {
                db.savePending(row.copy(lastError = error.message.orEmpty()))
            } else {
                db.deletePending(row.checksum)
            }
            throw error
        }
        if (put.duplicateJob) {
            val duplicate = row.copy(stage = PendingStage.DUPLICATE_JOB, master = put.master, asset = put.asset)
            db.savePending(duplicate)
            return if (confirmIfInICloud(duplicate)) Result.UPLOADED else Result.AWAITING
        }
        val submitted = row.copy(stage = PendingStage.SUBMITTED, jobId = put.jobId, master = put.master, asset = put.asset)
        db.savePending(submitted)
        return finishJob(submitted)
    }

    private suspend fun finishJob(row: PendingUpload, maxWaitMs: Long = 0L): Result {
        try {
            uploader.waitForJob(row.jobId, row.size, maxWaitMs) { percent ->
                progress(Progress("iCloud 处理中", "iCloud 正在处理 ${row.displayName}：${percent.toInt()}%"))
            }
        } catch (error: UploadException) {
            if (error.mayHaveBeenAccepted) {
                db.savePending(row.copy(lastError = error.message.orEmpty()))
            } else {
                db.deletePending(row.checksum)
            }
            throw error
        }
        return if (confirmIfInICloud(row, attempts = 4)) Result.UPLOADED else Result.AWAITING
    }

    /**
     * Looks the upload up in iCloud. Confirmed means: the original with this
     * exact checksum exists, and the photo record pointing at it is not deleted.
     */
    private suspend fun confirmIfInICloud(row: PendingUpload, attempts: Int = 1): Boolean {
        for (attempt in 1..attempts) {
            if (isInICloud(row)) {
                db.recordUploaded(row.checksum, row.displayName, row.size, row.master, row.asset, row.dsid)
                db.deletePending(row.checksum)
                db.setForced(row.checksum, false)
                db.clearRejected(row.checksum)
                return true
            }
            if (attempt < attempts) delay(2000L * attempt)
        }
        return false
    }

    private suspend fun isInICloud(row: PendingUpload): Boolean {
        if (db.remoteByChecksum(row.checksum) != null) return true
        val names = listOf(row.master, row.asset, row.checksum).filter { it.isNotBlank() }.distinct()
        val records = try {
            cloudKit.lookup(names, PhotoZone.PRIMARY)
        } catch (error: TransientAppleException) {
            return false
        } catch (error: AppleProtocolException) {
            // Cannot tell right now; the lock stays and a later run asks again.
            return false
        }
        var masterName = ""
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.text("recordType") != "CPLMaster" || record.has("serverErrorCode")) continue
            val checksum = record.optJSONObject("fields")?.optJSONObject("resOriginalRes")
                ?.optJSONObject("value").text("fileChecksum")
            if (AppleChecksum.normalize(checksum) == row.checksum) masterName = record.text("recordName")
        }
        if (masterName.isEmpty()) return false
        if (row.asset.isBlank()) return true
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.text("recordType") != "CPLAsset" || record.has("serverErrorCode")) continue
            val fields = record.optJSONObject("fields") ?: continue
            val master = fields.optJSONObject("masterRef")?.optJSONObject("value").text("recordName")
            val deleted = fields.optJSONObject("isDeleted")?.optInt("value", 0) == 1
            if (master == masterName && !deleted) return true
        }
        return false
    }

    /** Continues or settles uploads that an earlier run left unconfirmed. */
    private suspend fun resolvePending(dsid: String) {
        val rows = db.pendingAll().filter { it.dsid == dsid }
        if (rows.isEmpty()) return
        log("正在确认之前未完成的 ${rows.size} 个上传。")
        for (row in rows) {
            coroutineContext.ensureActive()
            progress(Progress("确认上次上传", "正在确认 ${row.displayName} 是否已在 iCloud。"))
            if (db.remoteByChecksum(row.checksum) != null) {
                // The fresh index already shows it: nothing left to wait for.
                confirmIfInICloud(row)
                log("${row.displayName} 已确认在 iCloud。")
                continue
            }
            try {
                when (row.stage) {
                    PendingStage.SENDING -> db.deletePending(row.checksum)
                    PendingStage.FILE_RECEIVED -> {
                        val singleFile = JSONObject(row.singleFileJson)
                        PhotosUploader.validateSingleFile(singleFile, row.size)
                        if (submit(row, singleFile) == Result.UPLOADED) log("${row.displayName} 已确认在 iCloud。")
                    }
                    PendingStage.SUBMITTED -> {
                        if (finishJob(row, maxWaitMs = 2 * 60 * 1000L) == Result.UPLOADED) log("${row.displayName} 已确认在 iCloud。")
                    }
                    PendingStage.DUPLICATE_JOB -> {
                        if (confirmIfInICloud(row)) log("${row.displayName} 已确认在 iCloud。")
                    }
                }
            } catch (error: SessionExpiredException) {
                throw error
            } catch (error: WebAccessDeniedException) {
                throw error
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                log("${row.displayName} 暂时无法确认：${error.message}")
            }
            val still = db.pending(row.checksum) ?: continue
            if (System.currentTimeMillis() - still.createdAt > PENDING_RELEASE_MS && db.remoteByChecksum(row.checksum) == null) {
                db.deletePending(row.checksum)
                log("${row.displayName} 超过 24 小时仍没出现在 iCloud，已解除锁定，下次会重新上传。")
            }
        }
    }
}

/** Rough upload time on a modest uplink (512 KB/s) plus Apple's processing. */
fun estimatedUploadMs(size: Long): Long = size / 512 + 30_000L

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    else -> "${maxOf(1L, (bytes + 1023) / 1024)} KB"
}
