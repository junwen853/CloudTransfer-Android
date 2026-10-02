package io.github.junwen853.cloudtransfer.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.junwen853.cloudtransfer.backup.BackupState
import io.github.junwen853.cloudtransfer.backup.BackupWorker
import io.github.junwen853.cloudtransfer.backup.CheckSummary
import io.github.junwen853.cloudtransfer.backup.DateRange
import io.github.junwen853.cloudtransfer.backup.RunMode
import io.github.junwen853.cloudtransfer.backup.formatBytes
import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.data.PlanCategory
import io.github.junwen853.cloudtransfer.data.SessionStore
import io.github.junwen853.cloudtransfer.data.Settings
import io.github.junwen853.cloudtransfer.media.LocalMediaStore
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(onLogin: () -> Unit, onOpenList: (PlanCategory) -> Unit) {
    val context = LocalContext.current
    val sessions = remember { SessionStore.get(context) }
    val settings = remember { Settings(context) }
    val running by BackupState.running.collectAsState()
    val summary by BackupState.summary.collectAsState()
    val error by BackupState.error.collectAsState()
    val log by BackupState.log.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh++ }
    }
    val session = remember(refresh, running) { sessions.load() }
    var range by remember { mutableStateOf<DateRange?>(null) }
    var showRangePicker by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("iCloud 照片搬家", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "把手机里还没在 iCloud 的照片和视频传到 iCloud 照片。用内容指纹判断是否已在 iCloud，不会重复上传。",
            style = MaterialTheme.typography.bodyMedium,
        )

        SectionCard("iCloud 账户") {
            if (session == null) {
                Text("还没有登录。登录在 App 内的 iCloud 网页里完成，App 不会看到你的密码。")
                Button(onClick = onLogin, enabled = running == null) { Text("登录 iCloud") }
            } else {
                Text("已登录（账户 ${session.maskedDsid()}，${if (session.isChina) "中国大陆 iCloud" else "全球 iCloud"}）")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLogin, enabled = running == null) { Text("重新登录") }
                    OutlinedButton(onClick = { confirmSignOut = true }, enabled = running == null) { Text("退出登录") }
                }
            }
        }

        PermissionCard(refresh) { refresh++ }

        SectionCard("检查和上传") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (range == null) {
                    Button(onClick = {}) { Text("全部") }
                    OutlinedButton(onClick = { showRangePicker = true }) { Text("按日期") }
                } else {
                    OutlinedButton(onClick = { range = null }) { Text("全部") }
                    Button(onClick = { showRangePicker = true }) { Text(range!!.label) }
                }
            }
            val ready = session != null && running == null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { BackupWorker.start(context, RunMode.CHECK, range) }, enabled = ready) { Text("检查") }
                Button(onClick = { BackupWorker.start(context, RunMode.UPLOAD, range) }, enabled = ready) { Text("检查并上传") }
            }
            Text(
                "“检查”只比对不上传；“检查并上传”会把还不在 iCloud 的上传上去，上传后会到 iCloud 里逐个确认。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        running?.let { run ->
            SectionCard(run.progress.phase) {
                Text(run.progress.message)
                if (run.progress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (run.progress.current.toFloat() / run.progress.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                OutlinedButton(onClick = { BackupWorker.stop(context) }) { Text("停止") }
            }
        }

        error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("没有完成", fontWeight = FontWeight.Bold)
                    Text(it)
                    if (it.contains("登录")) Button(onClick = onLogin) { Text("重新登录 iCloud") }
                }
            }
        }

        summary?.let { checked ->
            SummaryCard(checked, running == null && session != null, onOpenList) {
                BackupWorker.start(context, RunMode.UPLOAD, checked.range)
            }
        }

        NightlyCard(settings, session != null)

        SectionCard("查重方式") {
            var samePicture by remember { mutableStateOf(settings.samePictureCheck) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("也按画面识别")
                    Text(
                        "改过名、去掉了位置信息的同一张照片，只要拍摄时间相同、画面一致，也算已在 iCloud。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = samePicture, onCheckedChange = {
                    samePicture = it
                    settings.samePictureCheck = it
                })
            }
            OutlinedButton(
                onClick = { BackupWorker.start(context, RunMode.CHECK, range, forceFullIndex = true) },
                enabled = session != null && running == null,
            ) { Text("重新完整读取 iCloud 后检查") }
        }

        if (log.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            SectionCard("记录") {
                val lines = if (expanded) log.takeLast(200) else log.takeLast(6)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开全部") }
            }
        }
        Spacer(Modifier.padding(24.dp))
    }

    if (showRangePicker) {
        DateRangeDialog(onDismiss = { showRangePicker = false }) {
            range = it
            showRangePicker = false
        }
    }
    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("退出 iCloud 登录？") },
            text = { Text("会删除本机保存的登录信息和 iCloud 索引；已上传的照片不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    sessions.clear()
                    kotlin.concurrent.thread { AppDatabase.get(context).clearAccountState() }
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    confirmSignOut = false
                    refresh++
                }) { Text("退出") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun PermissionCard(refreshKey: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val full = remember(refreshKey) { LocalMediaStore.hasFullReadAccess(context) }
    val partial = remember(refreshKey) { LocalMediaStore.hasPartialReadAccess(context) }
    val location = remember(refreshKey) { LocalMediaStore.hasLocationAccess(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    if (full && location) return
    SectionCard("需要权限") {
        if (!full) {
            Text(
                if (partial) {
                    "现在只允许了访问部分照片，只能检查你选中的那些。要备份整个相册，请改为“全部允许”。"
                } else {
                    "需要读取照片和视频，才能知道哪些还没在 iCloud。"
                },
            )
        }
        if (!location) {
            Text("需要“访问照片位置信息”：没有它，Android 会把照片的拍摄地点去掉，上传的就不是原图了。")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val permissions = LocalMediaStore.readPermissions().toMutableList()
                permissions += Manifest.permission.ACCESS_MEDIA_LOCATION
                if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
                launcher.launch(permissions.toTypedArray())
            }) { Text("授权") }
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("打开系统设置") }
        }
    }
}

@Composable
private fun SummaryCard(summary: CheckSummary, idle: Boolean, onOpenList: (PlanCategory) -> Unit, onUpload: () -> Unit) {
    val time = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(summary.finishedAt))
    SectionCard("检查结果（${summary.rangeLabel}，$time）") {
        Text("手机里 ${summary.total} 个，iCloud 共 ${summary.iCloudTotal} 个原始文件。", style = MaterialTheme.typography.bodySmall)
        val rows = listOf(
            Triple(PlanCategory.UPLOADED, "本次已上传并确认", summary.uploaded),
            Triple(PlanCategory.TO_UPLOAD, "还不在 iCloud（${formatBytes(summary.toUploadBytes)}）", summary.toUpload),
            Triple(PlanCategory.IN_ICLOUD, "已在 iCloud（文件完全相同）", summary.inICloud),
            Triple(PlanCategory.SAME_PICTURE_IN_ICLOUD, "已在 iCloud（同一张照片，文件不同）", summary.samePicture),
            Triple(PlanCategory.AWAITING_CONFIRMATION, "已上传，等 iCloud 确认", summary.awaiting),
            Triple(PlanCategory.DELETED_IN_ICLOUD, "以前传过，iCloud 里已删除", summary.deletedInICloud),
            Triple(PlanCategory.LOCAL_DUPLICATE, "相册里重复的同一文件", summary.localDuplicates),
            Triple(PlanCategory.BLOCKED, "iCloud 不接收（格式或大小）", summary.blocked),
            Triple(PlanCategory.UNREADABLE, "手机读取失败", summary.unreadable),
            Triple(PlanCategory.FAILED, "上传失败", summary.failed),
        )
        rows.filter { it.third > 0 }.forEach { (category, label, count) ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpenList(category) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, Modifier.weight(1f))
                Text("$count", fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("›", color = Color.Gray)
            }
            HorizontalDivider()
        }
        if (summary.toUpload > 0) {
            Button(onClick = onUpload, enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text("上传这 ${summary.toUpload} 个（${formatBytes(summary.toUploadBytes)}）")
            }
        }
    }
}

@Composable
private fun NightlyCard(settings: Settings, loggedIn: Boolean) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.nightlyEnabled) }
    var hour by remember { mutableIntStateOf(settings.nightlyHour) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var chargingOnly by remember { mutableStateOf(settings.chargingOnly) }
    var hourMenu by remember { mutableStateOf(false) }
    fun apply() = BackupWorker.scheduleNightly(context, replace = true)
    SectionCard("自动备份") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("每天夜里自动上传新照片")
                Text("只上传完全不在 iCloud 的；按画面判断为已有的不会自动上传。", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = enabled, enabled = loggedIn || enabled, onCheckedChange = {
                enabled = it
                settings.nightlyEnabled = it
                apply()
            })
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("开始时间", Modifier.weight(1f))
                TextButton(onClick = { hourMenu = true }) { Text(String.format(Locale.US, "%02d:00 以后", hour)) }
                DropdownMenu(expanded = hourMenu, onDismissRequest = { hourMenu = false }) {
                    (0..23).forEach { value ->
                        DropdownMenuItem(text = { Text(String.format(Locale.US, "%02d:00", value)) }, onClick = {
                            hour = value
                            settings.nightlyHour = value
                            hourMenu = false
                            apply()
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("只用 Wi-Fi", Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = {
                    wifiOnly = it
                    settings.wifiOnly = it
                    apply()
                })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("只在充电时", Modifier.weight(1f))
                Switch(checked = chargingOnly, onCheckedChange = {
                    chargingOnly = it
                    settings.chargingOnly = it
                    apply()
                })
            }
            val last = settings.lastNightlyResult
            if (last.isNotEmpty()) Text("上次：$last", style = MaterialTheme.typography.bodySmall)
            val power = context.getSystemService(android.os.PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(context.packageName)) {
                Text(
                    "建议允许 App 不受电池优化限制：否则 Android 只给后台任务约 10 分钟，大视频会留到下次或需要手动上传。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = {
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    runCatching { context.startActivity(intent) }
                }) { Text("允许后台运行") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(onDismiss: () -> Unit, onPicked: (DateRange) -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = {
                    // The picker returns UTC midnight of the chosen days; use them as local days.
                    val zone = ZoneId.systemDefault()
                    val start = Instant.ofEpochMilli(state.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                    val end = state.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() } ?: start
                    val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
                    val to = end.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
                    onPicked(DateRange(from, to, label(start, end)))
                },
            ) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DateRangePicker(state = state, modifier = Modifier.weight(1f))
    }
}

private fun label(start: LocalDate, end: LocalDate): String =
    if (start == end) "${start.year}年${start.monthValue}月${start.dayOfMonth}日" else
        "${start.year}/${start.monthValue}/${start.dayOfMonth}–${end.year}/${end.monthValue}/${end.dayOfMonth}"
