package io.github.junwen853.cloudtransfer.media

/** Which phone media iCloud Photos accepts through its web upload. */
object MediaFormats {
    /** Apple's web uploader refuses files of 500 MiB and more. */
    const val MAX_UPLOAD_BYTES = 500L * 1024 * 1024
    const val MIN_UPLOAD_BYTES = 125L
    const val MAX_FILENAME_LENGTH = 255

    private val RAW = setOf("dng", "cr2", "cr3", "nef", "arw", "raf", "orf", "rw2", "pef", "srw")
    private val OTHER_VIDEO = setOf("3gp", "3g2", "mkv", "avi", "webm", "wmv", "flv", "mts", "m2ts", "ts")

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot >= 0 && dot < name.length - 1) name.substring(dot + 1).lowercase() else ""
    }

    /** jpeg, heif, png, webp, gif, tiff, raw, video, other-video or "" (unknown). */
    fun format(name: String, mimeType: String = ""): String {
        val byExtension = when (val extension = extension(name)) {
            "jpg", "jpeg" -> "jpeg"
            "heic", "heif" -> "heif"
            "png" -> "png"
            "webp" -> "webp"
            "gif" -> "gif"
            "tif", "tiff" -> "tiff"
            "mp4", "mov", "m4v" -> "video"
            in RAW -> "raw"
            in OTHER_VIDEO -> "other-video"
            else -> ""
        }
        if (byExtension.isNotEmpty()) return byExtension
        return when (mimeType.lowercase()) {
            "image/jpeg" -> "jpeg"
            "image/heic", "image/heif" -> "heif"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/tiff" -> "tiff"
            "video/mp4", "video/quicktime" -> "video"
            else -> if (mimeType.startsWith("video/")) "other-video" else ""
        }
    }

    /**
     * Why iCloud cannot take this file, or "" if it can. Motion photos are
     * single image files (the clip is embedded) and upload as photos.
     */
    fun blockReason(name: String, mimeType: String, size: Long, isVideo: Boolean): String {
        val format = format(name, mimeType)
        val label = extension(name).uppercase().ifEmpty { "无扩展名" }
        return when {
            name.isBlank() -> "文件没有名字"
            name.length > MAX_FILENAME_LENGTH -> "文件名超过 Apple 上限 $MAX_FILENAME_LENGTH 个字符"
            size >= MAX_UPLOAD_BYTES -> "超过 iCloud 网页上传上限 500 MB，需要用 iPhone、iPad、Mac 或 Windows 版 iCloud 上传"
            size in 1..MIN_UPLOAD_BYTES -> "文件太小（不超过 $MIN_UPLOAD_BYTES 字节），Apple 不接收"
            format == "gif" -> "iCloud 不接收 GIF 动图：Apple 上传接口和 iCloud 网页都会拒收"
            format == "other-video" || (isVideo && format != "video") -> "iCloud 照片只接收 MP4/MOV/M4V 视频，$label 需要先转换"
            format in setOf("jpeg", "heif", "png", "webp", "tiff", "raw", "video") -> ""
            else -> "iCloud 照片网页上传不接收 $label 格式"
        }
    }
}
