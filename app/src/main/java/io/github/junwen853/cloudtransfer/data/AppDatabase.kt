package io.github.junwen853.cloudtransfer.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.junwen853.cloudtransfer.icloud.RemoteItem

/**
 * Local state: the copy of the iCloud index, cached content checksums of the
 * phone's media, the upload ledger and in-flight upload checkpoints.
 */
class AppDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "cloudtransfer.db", null, 2) {

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) { instance ?: AppDatabase(context).also { instance = it } }

        const val REMOTE = "remote_item"
        const val REMOTE_BUILD = "remote_item_build"

        private fun remoteTableSql(name: String) = """
            CREATE TABLE IF NOT EXISTS $name (
                identity TEXT PRIMARY KEY,
                zone TEXT NOT NULL,
                record_name TEXT NOT NULL,
                checksum TEXT NOT NULL,
                size INTEGER NOT NULL,
                companion_checksum TEXT NOT NULL,
                companion_size INTEGER NOT NULL,
                filename TEXT NOT NULL,
                asset_date INTEGER NOT NULL,
                tz_offset INTEGER,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                hidden INTEGER NOT NULL
            )
        """.trimIndent()

        private fun remoteIndexesSql(name: String) = listOf(
            "CREATE INDEX IF NOT EXISTS ${name}_checksum ON $name(checksum)",
            "CREATE INDEX IF NOT EXISTS ${name}_companion ON $name(companion_checksum)",
            "CREATE INDEX IF NOT EXISTS ${name}_date ON $name(asset_date)",
        )
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL(remoteTableSql(REMOTE))
        remoteIndexesSql(REMOTE).forEach(db::execSQL)
        db.execSQL(
            """
            CREATE TABLE local_hash (
                media_id INTEGER PRIMARY KEY,
                size INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                checksum TEXT NOT NULL,
                visual TEXT NOT NULL DEFAULT '',
                exif_time TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE uploaded (
                checksum TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                master TEXT NOT NULL,
                asset TEXT NOT NULL,
                dsid TEXT NOT NULL,
                uploaded_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE pending (
                checksum TEXT PRIMARY KEY,
                media_id INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                capture_time INTEGER NOT NULL,
                dsid TEXT NOT NULL,
                stage TEXT NOT NULL,
                import_group TEXT NOT NULL DEFAULT '',
                single_file TEXT NOT NULL DEFAULT '',
                job_id TEXT NOT NULL DEFAULT '',
                master TEXT NOT NULL DEFAULT '',
                asset TEXT NOT NULL DEFAULT '',
                last_error TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE TABLE forced (checksum TEXT PRIMARY KEY, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE remote_visual (identity TEXT PRIMARY KEY, hash TEXT NOT NULL)")
        createRejectedTable(db)
        db.execSQL(
            """
            CREATE TABLE plan_item (
                media_id INTEGER PRIMARY KEY,
                is_video INTEGER NOT NULL,
                display_name TEXT NOT NULL,
                size INTEGER NOT NULL,
                date_taken INTEGER NOT NULL,
                checksum TEXT NOT NULL,
                category TEXT NOT NULL,
                detail TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createRejectedTable(db)
    }

    private fun createRejectedTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS rejected (checksum TEXT PRIMARY KEY, reason TEXT NOT NULL, at INTEGER NOT NULL)")
    }

    // ---- meta ----

    fun meta(key: String): String? =
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun setMeta(key: String, value: String?) {
        if (value == null) {
            writableDatabase.delete("meta", "key = ?", arrayOf(key))
        } else {
            writableDatabase.insertWithOnConflict(
                "meta", null,
                ContentValues().apply {
                    put("key", key)
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    // ---- remote index ----

    fun resetBuildTable() {
        writableDatabase.execSQL("DROP TABLE IF EXISTS $REMOTE_BUILD")
        writableDatabase.execSQL(remoteTableSql(REMOTE_BUILD))
    }

    /** Inserts or replaces items; returns how many identities were new to the table. */
    fun upsertRemote(table: String, items: List<RemoteItem>): Int {
        if (items.isEmpty()) return 0
        val db = writableDatabase
        var added = 0
        db.beginTransaction()
        try {
            db.compileStatement("SELECT COUNT(*) FROM $table WHERE identity = ?").use { exists ->
                for (item in items) {
                    exists.bindString(1, item.identity)
                    if (exists.simpleQueryForLong() == 0L) added++
                    db.insertWithOnConflict(table, null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added
    }

    fun remoteCount(table: String = REMOTE): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Replaces the live index with the finished build in one step. */
    fun promoteBuildTable() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS $REMOTE")
            db.execSQL("ALTER TABLE $REMOTE_BUILD RENAME TO $REMOTE")
            remoteIndexesSql(REMOTE).forEach(db::execSQL)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearRemote() {
        writableDatabase.execSQL("DELETE FROM $REMOTE")
        writableDatabase.execSQL("DROP TABLE IF EXISTS $REMOTE_BUILD")
    }

    fun remoteByChecksum(checksum: String): RemoteItem? =
        readableDatabase.rawQuery(
            "SELECT * FROM $REMOTE WHERE checksum = ? OR companion_checksum = ? LIMIT 1",
            arrayOf(checksum, checksum),
        ).use { if (it.moveToFirst()) it.toRemoteItem() else null }

    /** All checksums in the index (original and Live Photo motion parts). */
    fun remoteChecksums(): HashSet<String> {
        val result = HashSet<String>()
        readableDatabase.rawQuery("SELECT checksum, companion_checksum FROM $REMOTE", null).use {
            while (it.moveToNext()) {
                it.getString(0).takeIf { value -> value.isNotEmpty() }?.let(result::add)
                it.getString(1).takeIf { value -> value.isNotEmpty() }?.let(result::add)
            }
        }
        return result
    }

    /** Images captured within [fromMs, toMs] (UTC) — candidates for the picture comparison. */
    fun remoteByCaptureWindow(fromMs: Long, toMs: Long): List<RemoteItem> =
        readableDatabase.rawQuery(
            "SELECT * FROM $REMOTE WHERE asset_date BETWEEN ? AND ?",
            arrayOf(fromMs.toString(), toMs.toString()),
        ).use { cursor ->
            val items = ArrayList<RemoteItem>()
            while (cursor.moveToNext()) items += cursor.toRemoteItem()
            items
        }

    private fun RemoteItem.toValues() = ContentValues().apply {
        put("identity", identity)
        put("zone", zone)
        put("record_name", recordName)
        put("checksum", checksum)
        put("size", size)
        put("companion_checksum", companionChecksum)
        put("companion_size", companionSize)
        put("filename", filename)
        put("asset_date", assetDate)
        if (timeZoneOffsetSeconds == null) putNull("tz_offset") else put("tz_offset", timeZoneOffsetSeconds)
        put("width", width)
        put("height", height)
        put("hidden", if (hidden) 1 else 0)
    }

    private fun Cursor.toRemoteItem() = RemoteItem(
        identity = getString(getColumnIndexOrThrow("identity")),
        zone = getString(getColumnIndexOrThrow("zone")),
        recordName = getString(getColumnIndexOrThrow("record_name")),
        checksum = getString(getColumnIndexOrThrow("checksum")),
        size = getLong(getColumnIndexOrThrow("size")),
        companionChecksum = getString(getColumnIndexOrThrow("companion_checksum")),
        companionSize = getLong(getColumnIndexOrThrow("companion_size")),
        filename = getString(getColumnIndexOrThrow("filename")),
        assetDate = getLong(getColumnIndexOrThrow("asset_date")),
        timeZoneOffsetSeconds = getColumnIndexOrThrow("tz_offset").let { if (isNull(it)) null else getInt(it) },
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        hidden = getInt(getColumnIndexOrThrow("hidden")) != 0,
    )

    // ---- local checksum cache ----

    class LocalHash(val checksum: String, val visual: String, val exifTime: String)

    fun localHash(mediaId: Long, size: Long, dateModified: Long): LocalHash? =
        readableDatabase.rawQuery(
            "SELECT checksum, visual, exif_time FROM local_hash WHERE media_id = ? AND size = ? AND date_modified = ?",
            arrayOf(mediaId.toString(), size.toString(), dateModified.toString()),
        ).use { if (it.moveToFirst()) LocalHash(it.getString(0), it.getString(1), it.getString(2)) else null }

    fun saveLocalHash(mediaId: Long, size: Long, dateModified: Long, checksum: String, visual: String = "", exifTime: String = "") {
        writableDatabase.insertWithOnConflict(
            "local_hash", null,
            ContentValues().apply {
                put("media_id", mediaId)
                put("size", size)
                put("date_modified", dateModified)
                put("checksum", checksum)
                put("visual", visual)
                put("exif_time", exifTime)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteLocalHash(mediaId: Long) {
        writableDatabase.delete("local_hash", "media_id = ?", arrayOf(mediaId.toString()))
    }

    fun saveLocalVisual(mediaId: Long, visual: String, exifTime: String) {
        writableDatabase.update(
            "local_hash",
            ContentValues().apply {
                put("visual", visual)
                put("exif_time", exifTime)
            },
            "media_id = ?", arrayOf(mediaId.toString()),
        )
    }

    // ---- picture hashes of iCloud thumbnails ----

    /** Cached signature of an iCloud thumbnail, stored as VisualHash.encode text. */
    fun remoteVisual(identity: String): String? =
        readableDatabase.rawQuery("SELECT hash FROM remote_visual WHERE identity = ?", arrayOf(identity)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun saveRemoteVisual(identity: String, encoded: String) {
        writableDatabase.insertWithOnConflict(
            "remote_visual", null,
            ContentValues().apply {
                put("identity", identity)
                put("hash", encoded)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- upload ledger ----

    class UploadedRow(val checksum: String, val uploadedAt: Long)

    /** Files this app uploaded to the account [dsid]. */
    fun uploadedRows(dsid: String): HashMap<String, UploadedRow> {
        val result = HashMap<String, UploadedRow>()
        readableDatabase.rawQuery("SELECT checksum, uploaded_at FROM uploaded WHERE dsid = ?", arrayOf(dsid)).use {
            while (it.moveToNext()) result[it.getString(0)] = UploadedRow(it.getString(0), it.getLong(1))
        }
        return result
    }

    fun isUploaded(checksum: String, dsid: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM uploaded WHERE checksum = ? AND dsid = ?", arrayOf(checksum, dsid)).use { it.moveToFirst() }

    fun recordUploaded(checksum: String, displayName: String, size: Long, master: String, asset: String, dsid: String) {
        writableDatabase.insertWithOnConflict(
            "uploaded", null,
            ContentValues().apply {
                put("checksum", checksum)
                put("display_name", displayName)
                put("size", size)
                put("master", master)
                put("asset", asset)
                put("dsid", dsid)
                put("uploaded_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    // ---- in-flight uploads ----

    fun pendingAll(): List<PendingUpload> =
        readableDatabase.rawQuery("SELECT * FROM pending ORDER BY created_at", null).use { cursor ->
            val result = ArrayList<PendingUpload>()
            while (cursor.moveToNext()) result += cursor.toPending()
            result
        }

    fun pending(checksum: String): PendingUpload? =
        readableDatabase.rawQuery("SELECT * FROM pending WHERE checksum = ?", arrayOf(checksum)).use {
            if (it.moveToFirst()) it.toPending() else null
        }

    /** Writes the checkpoint and reads it back, so a lost write never goes unnoticed. */
    fun savePending(pending: PendingUpload) {
        writableDatabase.insertWithOnConflict(
            "pending", null,
            ContentValues().apply {
                put("checksum", pending.checksum)
                put("media_id", pending.mediaId)
                put("display_name", pending.displayName)
                put("size", pending.size)
                put("capture_time", pending.captureTime)
                put("dsid", pending.dsid)
                put("stage", pending.stage.name)
                put("import_group", pending.importGroup)
                put("single_file", pending.singleFileJson)
                put("job_id", pending.jobId)
                put("master", pending.master)
                put("asset", pending.asset)
                put("last_error", pending.lastError)
                put("created_at", pending.createdAt)
                put("updated_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        val stored = pending(pending.checksum)
        check(stored != null && stored.stage == pending.stage && stored.jobId == pending.jobId &&
            stored.singleFileJson == pending.singleFileJson) { "上传检查点保存后回读不一致。" }
    }

    fun deletePending(checksum: String) {
        writableDatabase.delete("pending", "checksum = ?", arrayOf(checksum))
    }

    private fun Cursor.toPending() = PendingUpload(
        checksum = getString(getColumnIndexOrThrow("checksum")),
        mediaId = getLong(getColumnIndexOrThrow("media_id")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        size = getLong(getColumnIndexOrThrow("size")),
        captureTime = getLong(getColumnIndexOrThrow("capture_time")),
        dsid = getString(getColumnIndexOrThrow("dsid")),
        stage = runCatching { PendingStage.valueOf(getString(getColumnIndexOrThrow("stage"))) }.getOrDefault(PendingStage.SENDING),
        importGroup = getString(getColumnIndexOrThrow("import_group")),
        singleFileJson = getString(getColumnIndexOrThrow("single_file")),
        jobId = getString(getColumnIndexOrThrow("job_id")),
        master = getString(getColumnIndexOrThrow("master")),
        asset = getString(getColumnIndexOrThrow("asset")),
        lastError = getString(getColumnIndexOrThrow("last_error")),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        updatedAt = getLong(getColumnIndexOrThrow("updated_at")),
    )

    // ---- files Apple refused ----

    fun rejectedReasons(): HashMap<String, String> {
        val result = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT checksum, reason FROM rejected", null).use {
            while (it.moveToNext()) result[it.getString(0)] = it.getString(1)
        }
        return result
    }

    fun setRejected(checksum: String, reason: String) {
        writableDatabase.insertWithOnConflict(
            "rejected", null,
            ContentValues().apply {
                put("checksum", checksum)
                put("reason", reason)
                put("at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun clearRejected(checksum: String) {
        writableDatabase.delete("rejected", "checksum = ?", arrayOf(checksum))
    }

    // ---- "upload anyway" choices ----

    fun forcedChecksums(): HashSet<String> {
        val result = HashSet<String>()
        readableDatabase.rawQuery("SELECT checksum FROM forced", null).use { while (it.moveToNext()) result += it.getString(0) }
        return result
    }

    fun setForced(checksum: String, forced: Boolean) {
        if (forced) {
            writableDatabase.insertWithOnConflict(
                "forced", null,
                ContentValues().apply {
                    put("checksum", checksum)
                    put("created_at", System.currentTimeMillis())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        } else {
            writableDatabase.delete("forced", "checksum = ?", arrayOf(checksum))
        }
    }

    // ---- last check result ----

    fun replacePlan(items: List<PlanRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("plan_item", null, null)
            for (item in items) {
                db.insert(
                    "plan_item", null,
                    ContentValues().apply {
                        put("media_id", item.mediaId)
                        put("is_video", if (item.isVideo) 1 else 0)
                        put("display_name", item.displayName)
                        put("size", item.size)
                        put("date_taken", item.dateTaken)
                        put("checksum", item.checksum)
                        put("category", item.category.name)
                        put("detail", item.detail)
                    },
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updatePlanCategory(mediaId: Long, category: PlanCategory, detail: String) {
        writableDatabase.update(
            "plan_item",
            ContentValues().apply {
                put("category", category.name)
                put("detail", detail)
            },
            "media_id = ?", arrayOf(mediaId.toString()),
        )
    }

    fun planItems(category: PlanCategory? = null): List<PlanRow> {
        val cursor = if (category == null) {
            readableDatabase.rawQuery("SELECT * FROM plan_item ORDER BY date_taken DESC", null)
        } else {
            readableDatabase.rawQuery(
                "SELECT * FROM plan_item WHERE category = ? ORDER BY date_taken DESC", arrayOf(category.name),
            )
        }
        return cursor.use {
            val result = ArrayList<PlanRow>()
            while (it.moveToNext()) {
                result += PlanRow(
                    mediaId = it.getLong(it.getColumnIndexOrThrow("media_id")),
                    isVideo = it.getInt(it.getColumnIndexOrThrow("is_video")) != 0,
                    displayName = it.getString(it.getColumnIndexOrThrow("display_name")),
                    size = it.getLong(it.getColumnIndexOrThrow("size")),
                    dateTaken = it.getLong(it.getColumnIndexOrThrow("date_taken")),
                    checksum = it.getString(it.getColumnIndexOrThrow("checksum")),
                    category = runCatching { PlanCategory.valueOf(it.getString(it.getColumnIndexOrThrow("category"))) }
                        .getOrDefault(PlanCategory.BLOCKED),
                    detail = it.getString(it.getColumnIndexOrThrow("detail")),
                )
            }
            result
        }
    }

    /** Forgets everything tied to an account (sign-out or a different Apple ID). */
    fun clearAccountState() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("meta", "key LIKE 'index.%'", null)
            db.execSQL("DELETE FROM $REMOTE")
            db.execSQL("DROP TABLE IF EXISTS $REMOTE_BUILD")
            db.delete("plan_item", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

enum class PendingStage {
    /** File bytes may be on their way; nothing was added to the library yet. */
    SENDING,

    /** Apple holds the file; the library import (putAsset) may or may not have happened. */
    FILE_RECEIVED,

    /** Apple accepted the import as a job; its result is not confirmed yet. */
    SUBMITTED,

    /** Apple already had a job for this import; only the index can tell the result. */
    DUPLICATE_JOB,
}

data class PendingUpload(
    val checksum: String,
    val mediaId: Long,
    val displayName: String,
    val size: Long,
    val captureTime: Long,
    val dsid: String,
    val stage: PendingStage,
    val importGroup: String = "",
    val singleFileJson: String = "",
    val jobId: String = "",
    val master: String = "",
    val asset: String = "",
    val lastError: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

enum class PlanCategory {
    /** The identical file is in iCloud. */
    IN_ICLOUD,

    /** The same picture is in iCloud as a different file (renamed, location or metadata removed). */
    SAME_PICTURE_IN_ICLOUD,

    /** This app uploaded it before, but it is no longer in iCloud (deleted there). */
    DELETED_IN_ICLOUD,

    /** An earlier upload has not been confirmed yet; it is not sent again. */
    AWAITING_CONFIRMATION,

    TO_UPLOAD,

    /** iCloud cannot take it (format or size). */
    BLOCKED,

    /** The phone could not read it. */
    UNREADABLE,

    /** Another copy of the same file in the gallery is planned already. */
    LOCAL_DUPLICATE,

    /** Uploaded and confirmed in this run. */
    UPLOADED,

    /** The upload in this run failed. */
    FAILED,
}

data class PlanRow(
    val mediaId: Long,
    val isVideo: Boolean,
    val displayName: String,
    val size: Long,
    val dateTaken: Long,
    val checksum: String,
    val category: PlanCategory,
    val detail: String,
)
