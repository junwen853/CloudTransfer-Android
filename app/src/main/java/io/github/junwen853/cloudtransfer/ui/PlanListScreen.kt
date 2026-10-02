package io.github.junwen853.cloudtransfer.ui

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.junwen853.cloudtransfer.backup.BackupState
import io.github.junwen853.cloudtransfer.backup.formatBytes
import io.github.junwen853.cloudtransfer.data.AppDatabase
import io.github.junwen853.cloudtransfer.data.PlanCategory
import io.github.junwen853.cloudtransfer.data.PlanRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun categoryTitle(category: PlanCategory): String = when (category) {
    PlanCategory.IN_ICLOUD -> "已在 iCloud（文件完全相同）"
    PlanCategory.SAME_PICTURE_IN_ICLOUD -> "已在 iCloud（同一张照片，文件不同）"
    PlanCategory.DELETED_IN_ICLOUD -> "以前传过，iCloud 里已删除"
    PlanCategory.AWAITING_CONFIRMATION -> "已上传，等 iCloud 确认"
    PlanCategory.TO_UPLOAD -> "还不在 iCloud"
    PlanCategory.BLOCKED -> "iCloud 不接收"
    PlanCategory.UNREADABLE -> "手机读取失败"
    PlanCategory.LOCAL_DUPLICATE -> "相册里重复的同一文件"
    PlanCategory.UPLOADED -> "本次已上传并确认"
    PlanCategory.FAILED -> "上传失败"
}

@Composable
fun PlanListScreen(category: PlanCategory, onBack: () -> Unit) {
    val context = LocalContext.current
    val db = remember { AppDatabase.get(context) }
    val version by BackupState.planVersion.collectAsState()
    var forcedVersion by remember { mutableStateOf(0) }
    val rows by produceState(initialValue = emptyList<PlanRow>(), category, version) {
        value = withContext(Dispatchers.IO) { db.planItems(category) }
    }
    val forced by produceState(initialValue = emptySet<String>(), forcedVersion) {
        value = withContext(Dispatchers.IO) { db.forcedChecksums() }
    }
    val scope = rememberCoroutineScope()
    val canForce = category == PlanCategory.SAME_PICTURE_IN_ICLOUD || category == PlanCategory.DELETED_IN_ICLOUD ||
        category == PlanCategory.FAILED
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("返回") }
            Column(Modifier.padding(start = 12.dp)) {
                Text(categoryTitle(category), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${rows.size} 个", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (canForce) {
            Text(
                "勾选“仍要上传”后，下次“检查并上传”会把它们也传上去。",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.mediaId }) { row ->
                PlanRowItem(row, canForce, row.checksum in forced) { checked ->
                    scope.launch {
                        withContext(Dispatchers.IO) { db.setForced(row.checksum, checked) }
                        forcedVersion++
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun PlanRowItem(row: PlanRow, canForce: Boolean, forced: Boolean, onForce: (Boolean) -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, row.mediaId) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, row) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).background(Color(0xFFE4E7EC))) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (row.isVideo) Text("▶", Modifier.align(Alignment.BottomEnd).padding(2.dp), color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            val date = if (row.dateTaken > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(row.dateTaken)) else "日期未知"
            Text("$date · ${formatBytes(row.size)}", style = MaterialTheme.typography.bodySmall)
            if (row.detail.isNotEmpty()) Text(row.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            if (canForce) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forced, onCheckedChange = onForce)
                    Text("仍要上传", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private val thumbnails = LruCache<Long, Bitmap>(200)

private fun loadThumbnail(context: Context, row: PlanRow): Bitmap? {
    thumbnails.get(row.mediaId)?.let { return it }
    val collection = if (row.isVideo) {
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    } else {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    }
    return runCatching {
        context.contentResolver.loadThumbnail(ContentUris.withAppendedId(collection, row.mediaId), Size(192, 192), null)
    }.getOrNull()?.also { thumbnails.put(row.mediaId, it) }
}
