package io.github.junwen853.cloudtransfer.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import io.github.junwen853.cloudtransfer.icloud.AppleChecksum
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

/** A photo or video in the phone's gallery. */
data class LocalMedia(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    /** Capture time from the file (UTC ms), 0 if the gallery does not know it. */
    val dateTaken: Long,
    val dateAdded: Long,
    val dateModified: Long,
    val width: Int,
    val height: Int,
    val relativePath: String,
) {
    /** Best known capture time: the shot itself, else when it arrived on the phone. */
    val captureTime: Long
        get() = when {
            dateTaken > 0 -> dateTaken
            dateAdded > 0 -> dateAdded
            else -> dateModified
        }
}

class LocalReadException(message: String) : IOException(message)

/** Gallery access. Files are always opened as originals, so location data is kept. */
class LocalMediaStore(private val context: Context) {

    companion object {
        fun readPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasFullReadAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 33) {
            granted(context, Manifest.permission.READ_MEDIA_IMAGES) && granted(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        /** Android 14+: the user allowed only some photos. */
        fun hasPartialReadAccess(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 34 && !hasFullReadAccess(context) &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

        /** Without it Android removes the GPS position from every file the app reads. */
        fun hasLocationAccess(context: Context): Boolean = granted(context, Manifest.permission.ACCESS_MEDIA_LOCATION)

        private fun granted(context: Context, permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** All gallery photos and videos, newest first; [from]/[to] limit the capture time (UTC ms). */
    fun list(from: Long = Long.MIN_VALUE, to: Long = Long.MAX_VALUE): List<LocalMedia> {
        val result = ArrayList<LocalMedia>()
        result += query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = false)
        result += query(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), isVideo = true)
        return result.filter { it.captureTime in from..to }.sortedByDescending { it.captureTime }
    }

    private fun query(collection: Uri, isVideo: Boolean): List<LocalMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        val items = ArrayList<LocalMedia>()
        context.contentResolver.query(collection, projection, "${MediaStore.MediaColumns.IS_PENDING} = 0", null, null)?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val modified = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                items += LocalMedia(
                    id = mediaId,
                    uri = ContentUris.withAppendedId(collection, mediaId),
                    isVideo = isVideo,
                    displayName = cursor.getString(name).orEmpty(),
                    mimeType = cursor.getString(mime).orEmpty(),
                    size = cursor.getLong(size),
                    dateTaken = if (cursor.isNull(taken)) 0L else cursor.getLong(taken),
                    dateAdded = if (cursor.isNull(added)) 0L else cursor.getLong(added) * 1000,
                    dateModified = if (cursor.isNull(modified)) 0L else cursor.getLong(modified) * 1000,
                    width = cursor.getInt(width),
                    height = cursor.getInt(height),
                    relativePath = cursor.getString(path).orEmpty(),
                )
            }
        }
        return items
    }

    /**
     * Opens the original file. Without ACCESS_MEDIA_LOCATION Android would hand
     * out a copy with the GPS position blanked, which is a different file than
     * the one in iCloud; that is refused instead.
     */
    fun openOriginal(media: LocalMedia): ParcelFileDescriptor {
        if (!hasLocationAccess(context)) {
            throw LocalReadException("没有“访问照片位置信息”权限，读取到的照片会被系统去掉拍摄地点，已停止。请在权限设置里允许。")
        }
        val uri = MediaStore.setRequireOriginal(media.uri)
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw LocalReadException("系统没有返回文件。")
        } catch (error: SecurityException) {
            throw LocalReadException("系统拒绝读取原始文件：${error.message}")
        } catch (error: UnsupportedOperationException) {
            throw LocalReadException("系统拒绝读取原始文件：${error.message}")
        } catch (error: IOException) {
            throw LocalReadException("文件无法打开：${error.message}")
        }
    }

    class ChecksumResult(val checksum: String, val size: Long)

    /** Apple checksum of the original bytes; fails if the file changes while it is read. */
    fun checksum(media: LocalMedia, checkCancelled: () -> Unit): ChecksumResult {
        openOriginal(media).use { descriptor ->
            val sizeBefore = descriptor.statSize
            if (sizeBefore <= 0) throw LocalReadException("文件为空。")
            val (checksum, read) = FileInputStream(descriptor.fileDescriptor).use { stream ->
                try {
                    AppleChecksum.of(stream, checkCancelled)
                } catch (error: IOException) {
                    throw LocalReadException("读取失败：${error.message}")
                }
            }
            if (read != sizeBefore || descriptor.statSize != sizeBefore) {
                throw LocalReadException("文件在读取时发生变化，请稍后重新检查。")
            }
            return ChecksumResult(checksum, read)
        }
    }

    fun openStream(media: LocalMedia): InputStream =
        ParcelFileDescriptor.AutoCloseInputStream(openOriginal(media))

    /** "yyyy:MM:dd HH:mm:ss" capture time written by the camera, or "". */
    fun exifCaptureTime(media: LocalMedia): String = try {
        openStream(media).use { stream ->
            val exif = ExifInterface(stream)
            exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                ?: ""
        }
    } catch (_: Exception) {
        ""
    }
}
