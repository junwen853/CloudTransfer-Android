package io.github.junwen853.cloudtransfer.backup

import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.data.PlanCategory
import io.github.junwen853.cloudtransfer.data.PlanRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Progress(
    val phase: String,
    val message: String,
    val current: Long = 0,
    val total: Long = 0,
)

enum class RunMode { CHECK, UPLOAD, NIGHTLY }

data class RunState(val mode: RunMode, val progress: Progress)

/** Counts of the last check, shown on the main screen. */
data class CheckSummary(
    val total: Int = 0,
    val inICloud: Int = 0,
    val samePicture: Int = 0,
    val deletedInICloud: Int = 0,
    val awaiting: Int = 0,
    val toUpload: Int = 0,
    val toUploadBytes: Long = 0,
    val blocked: Int = 0,
    val unreadable: Int = 0,
    val localDuplicates: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val iCloudTotal: Int = 0,
    val finishedAt: Long = 0,
    val rangeLabel: String = "",
    /** Range of the check, so "upload these" uploads exactly what was checked. */
    val range: DateRange? = null,
)

fun summarizePlan(rows: List<PlanRow>, range: DateRange?, iCloudTotal: Int, finishedAt: Long): CheckSummary {
    fun count(category: PlanCategory) = rows.count { it.category == category }
    return CheckSummary(
        total = rows.size,
        inICloud = count(PlanCategory.IN_ICLOUD),
        samePicture = count(PlanCategory.SAME_PICTURE_IN_ICLOUD),
        deletedInICloud = count(PlanCategory.DELETED_IN_ICLOUD),
        awaiting = count(PlanCategory.AWAITING_CONFIRMATION),
        toUpload = count(PlanCategory.TO_UPLOAD),
        toUploadBytes = rows.filter { it.category == PlanCategory.TO_UPLOAD }.sumOf { it.size },
        blocked = count(PlanCategory.BLOCKED),
        unreadable = count(PlanCategory.UNREADABLE),
        localDuplicates = count(PlanCategory.LOCAL_DUPLICATE),
        uploaded = count(PlanCategory.UPLOADED),
        failed = count(PlanCategory.FAILED),
        iCloudTotal = iCloudTotal,
        finishedAt = finishedAt,
        rangeLabel = range?.label ?: "全部照片和视频",
        range = range,
    )
}

/** The stored result of the last check, for showing after the app restarts. */
fun loadStoredSummary(db: AppDatabase): CheckSummary? {
    val finishedAt = db.meta("plan.at")?.toLongOrNull() ?: return null
    val rows = db.planItems()
    if (rows.isEmpty()) return null
    val label = db.meta("plan.range").orEmpty()
    val from = db.meta("plan.from")?.toLongOrNull()
    val to = db.meta("plan.to")?.toLongOrNull()
    val range = if (label.isNotEmpty() && from != null && to != null) DateRange(from, to, label) else null
    return summarizePlan(rows, range, db.remoteCount(), finishedAt)
}

/** App-wide state of the backup work, observed by the UI. */
object BackupState {
    private val _running = MutableStateFlow<RunState?>(null)
    val running: StateFlow<RunState?> = _running.asStateFlow()

    private val _summary = MutableStateFlow<CheckSummary?>(null)
    val summary: StateFlow<CheckSummary?> = _summary.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    /** Last error that stopped a run, shown until the next run starts. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Bumped whenever the stored plan changes, so lists reload. */
    private val _planVersion = MutableStateFlow(0)
    val planVersion: StateFlow<Int> = _planVersion.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    fun start(mode: RunMode) {
        _error.value = null
        _running.value = RunState(mode, Progress("准备", "正在开始。"))
    }

    fun progress(mode: RunMode, progress: Progress) {
        _running.value = RunState(mode, progress)
    }

    fun finish(error: String? = null) {
        _running.value = null
        _error.value = error
    }

    fun publish(summary: CheckSummary) {
        _summary.value = summary
        _planVersion.update { it + 1 }
    }

    fun planChanged() = _planVersion.update { it + 1 }

    /** Shows the stored result once after start, unless a run already published one. */
    fun restore(summary: CheckSummary?) {
        if (summary != null) _summary.compareAndSet(null, summary)
    }

    fun log(line: String) {
        android.util.Log.i("CloudTransfer", line)
        val stamped = "${timeFormat.format(Date())} $line"
        _log.update { (it + stamped).takeLast(300) }
    }
}
