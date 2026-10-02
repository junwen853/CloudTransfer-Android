package io.github.junwen853.cloudtransfer.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.junwen853.cloudtransfer.MainActivity
import io.github.junwen853.cloudtransfer.R
import io.github.junwen853.cloudtransfer.data.Settings
import io.github.junwen853.cloudtransfer.icloud.SessionExpiredException
import io.github.junwen853.cloudtransfer.icloud.WebAccessDeniedException
import androidx.work.WorkInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Runs checks and uploads as WorkManager work, shown as a foreground
 * notification, so they keep going when the app is in the background.
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val MANUAL_WORK = "manual"
        private const val NIGHTLY_WORK = "nightly"
        private const val KEY_MODE = "mode"
        private const val KEY_FROM = "from"
        private const val KEY_TO = "to"
        private const val KEY_LABEL = "label"
        private const val KEY_FORCE_FULL = "forceFull"
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_PROGRESS = 1
        private const val NOTIFICATION_ALERT = 2

        /** A background job without a foreground service is stopped after about 10 minutes. */
        private const val BACKGROUND_BUDGET_MS = 8 * 60 * 1000L

        /** Only one check or upload runs at a time. */
        val runLock = Mutex()

        fun start(context: Context, mode: RunMode, range: DateRange?, forceFullIndex: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInputData(
                    workDataOf(
                        KEY_MODE to mode.name,
                        KEY_FROM to (range?.from ?: Long.MIN_VALUE),
                        KEY_TO to (range?.to ?: Long.MAX_VALUE),
                        KEY_LABEL to range?.label,
                        KEY_FORCE_FULL to forceFullIndex,
                    ),
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK)
            if (BackupState.running.value?.mode == RunMode.NIGHTLY) {
                // Replacing the nightly work stops tonight's run and plans the next one.
                scheduleNightly(context, replace = true)
            }
        }

        /** Plans the next nightly run, or removes it when the automatic backup is off. */
        fun scheduleNightly(context: Context, replace: Boolean) {
            val settings = Settings(context)
            val workManager = WorkManager.getInstance(context)
            if (!settings.nightlyEnabled) {
                workManager.cancelUniqueWork(NIGHTLY_WORK)
                return
            }
            val now = Calendar.getInstance()
            val next = (now.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, settings.nightlyHour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (!after(now)) add(Calendar.DAY_OF_YEAR, 1)
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.chargingOnly)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setInitialDelay(next.timeInMillis - now.timeInMillis, TimeUnit.MILLISECONDS)
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MODE to RunMode.NIGHTLY.name))
                .build()
            workManager.enqueueUniqueWork(
                NIGHTLY_WORK,
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }

        fun ensureChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "备份进度", NotificationManager.IMPORTANCE_LOW),
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "备份结果和提醒", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        fun notifyAlert(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            ensureChannels(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openAppIntent(context))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ALERT, notification)
        }

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private val mode = runCatching { RunMode.valueOf(inputData.getString(KEY_MODE).orEmpty()) }.getOrDefault(RunMode.CHECK)
    private var lastNotified = 0L

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("准备中")

    private fun foregroundInfo(text: String, current: Long = 0, total: Long = 0): ForegroundInfo {
        ensureChannels(applicationContext)
        val title = when (mode) {
            RunMode.CHECK -> "正在检查哪些照片还没在 iCloud"
            RunMode.UPLOAD -> "正在上传到 iCloud 照片"
            RunMode.NIGHTLY -> "自动备份到 iCloud 照片"
        }
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent(applicationContext))
            .addAction(0, "停止", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
        if (total > 0) {
            builder.setProgress(1000, (1000 * current / total).toInt().coerceIn(0, 1000), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTIFICATION_PROGRESS, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_PROGRESS, builder.build())
        }
    }

    override suspend fun doWork(): Result {
        if (!runLock.tryLock()) {
            return if (mode == RunMode.NIGHTLY) Result.retry() else Result.success()
        }
        val startedAt = System.currentTimeMillis()
        var foreground = false
        try {
            try {
                setForeground(foregroundInfo("准备中"))
                foreground = true
            } catch (_: Exception) {
                // Android may refuse a foreground service for background work; the
                // run then has the normal job time limit (about 10 minutes).
            }
            BackupState.start(mode)
            coroutineScope {
                val notifications = launch {
                    BackupState.running.collect { run ->
                        val now = System.currentTimeMillis()
                        if (run == null || now - lastNotified < 1000) return@collect
                        lastNotified = now
                        runCatching { setForeground(foregroundInfo(run.progress.message, run.progress.current, run.progress.total)) }
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        runEngine(deadline = if (foreground) 0L else startedAt + BACKGROUND_BUDGET_MS)
                    }
                } finally {
                    notifications.cancel()
                }
            }
            BackupState.finish()
            if (mode == RunMode.NIGHTLY) scheduleNightly(applicationContext, replace = true)
            return Result.success()
        } catch (error: CancellationException) {
            BackupState.finish("已停止。")
            BackupState.log("已停止。")
            if (mode == RunMode.NIGHTLY && stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) {
                // Stopped by the user: tonight is done, the next night stays planned.
                scheduleNightly(applicationContext, replace = true)
            }
            throw error
        } catch (error: SessionExpiredException) {
            BackupState.finish(error.message)
            BackupState.log(error.message.orEmpty())
            if (mode == RunMode.NIGHTLY) {
                notifyAlert(applicationContext, "iCloud 需要重新登录", "自动备份没有进行：iCloud 登录已失效。打开 App 重新登录即可。")
                scheduleNightly(applicationContext, replace = true)
            }
            return Result.failure()
        } catch (error: WebAccessDeniedException) {
            BackupState.finish(error.message)
            BackupState.log(error.message.orEmpty())
            if (mode == RunMode.NIGHTLY) {
                notifyAlert(applicationContext, "iCloud 拒绝了网页访问", error.message.orEmpty())
                scheduleNightly(applicationContext, replace = true)
            }
            return Result.failure()
        } catch (error: Exception) {
            val message = error.message ?: error.javaClass.simpleName
            BackupState.finish(message)
            BackupState.log("出错：$message")
            if (mode == RunMode.NIGHTLY) {
                Settings(applicationContext).lastNightlyResult = "出错：$message"
                return Result.retry()
            }
            return Result.failure()
        } finally {
            runLock.unlock()
        }
    }

    private suspend fun runEngine(deadline: Long) {
        val label = inputData.getString(KEY_LABEL)
        val range = if (label != null) {
            DateRange(inputData.getLong(KEY_FROM, Long.MIN_VALUE), inputData.getLong(KEY_TO, Long.MAX_VALUE), label)
        } else {
            null
        }
        val engine = BackupEngine(applicationContext, mode)
        when (mode) {
            RunMode.CHECK -> engine.check(range, inputData.getBoolean(KEY_FORCE_FULL, false))
            RunMode.UPLOAD -> engine.upload(range)
            RunMode.NIGHTLY -> {
                val summary = engine.upload(null, deadline)
                val text = "上传 ${summary.uploaded} 个，失败 ${summary.failed} 个，${summary.inICloud} 个已在 iCloud。"
                val stamp = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())
                Settings(applicationContext).lastNightlyResult = "$stamp：$text"
                if (summary.uploaded > 0 || summary.failed > 0) notifyAlert(applicationContext, "自动备份完成", text)
            }
        }
    }
}
