package io.github.junwen853.cloudtransfer.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/**
 * Recognises the same picture when the files differ: removed location data,
 * edited metadata or a re-saved copy change the Apple checksum but not what
 * the photo shows.
 *
 * Both pictures are reduced to a GRID x GRID grey grid and compared by
 * correlation, which stays close to 1 through resizing, re-compression and
 * brightness changes, and does not hinge on any single cell.
 */
object VisualHash {
    const val GRID = 16

    /** Correlation of the grey grids at which two pictures count as the same. */
    const val SAME_PICTURE_MIN_CORRELATION = 0.95

    /** Stricter bar for a further copy of a photo that is on the phone already. */
    const val COPY_MIN_CORRELATION = 0.98

    /** Grey-level spread below which a picture is too flat to compare (a blank frame). */
    private const val MIN_SPREAD = 4.0

    /** Grey value (0-255) per cell of a size x size grid, row by row, from ARGB pixels. */
    fun greyGrid(pixels: IntArray, width: Int, height: Int, size: Int = GRID): DoubleArray {
        val grid = DoubleArray(size * size)
        for (row in 0 until size) {
            for (column in 0 until size) {
                val x0 = column * width / size
                val x1 = maxOf(x0 + 1, (column + 1) * width / size)
                val y0 = row * height / size
                val y1 = maxOf(y0 + 1, (row + 1) * height / size)
                var sum = 0.0
                var count = 0
                var y = y0
                while (y < y1 && y < height) {
                    var x = x0
                    while (x < x1 && x < width) {
                        val pixel = pixels[y * width + x]
                        sum += 0.299 * ((pixel shr 16) and 0xff) + 0.587 * ((pixel shr 8) and 0xff) + 0.114 * (pixel and 0xff)
                        count++
                        x++
                    }
                    y++
                }
                grid[row * size + column] = if (count > 0) sum / count else 0.0
            }
        }
        return grid
    }

    /** The grid turned clockwise by quarterTurns * 90 degrees. */
    fun rotate(grid: DoubleArray, size: Int, quarterTurns: Int): DoubleArray {
        var current = grid.copyOf()
        repeat(((quarterTurns % 4) + 4) % 4) {
            val next = DoubleArray(size * size)
            for (row in 0 until size) {
                for (column in 0 until size) {
                    next[column * size + (size - 1 - row)] = current[row * size + column]
                }
            }
            current = next
        }
        return current
    }

    /**
     * Pearson correlation of two grey grids: 1 for the same picture, whatever
     * its size, compression or brightness. Null if either picture is flat.
     */
    fun correlation(left: DoubleArray, right: DoubleArray): Double? {
        if (left.size != right.size || left.isEmpty()) return null
        val leftMean = left.average()
        val rightMean = right.average()
        var covariance = 0.0
        var leftSquares = 0.0
        var rightSquares = 0.0
        for (index in left.indices) {
            val l = left[index] - leftMean
            val r = right[index] - rightMean
            covariance += l * r
            leftSquares += l * l
            rightSquares += r * r
        }
        val leftSpread = kotlin.math.sqrt(leftSquares / left.size)
        val rightSpread = kotlin.math.sqrt(rightSquares / right.size)
        if (leftSpread < MIN_SPREAD || rightSpread < MIN_SPREAD) return null
        return covariance / kotlin.math.sqrt(leftSquares * rightSquares)
    }

    /**
     * Best correlation between any quarter turn of the phone's picture (its
     * orientation tag may differ) and iCloud's upright thumbnail.
     */
    fun bestCorrelation(local: DoubleArray, remoteUpright: DoubleArray, size: Int = GRID): Double? =
        (0 until 4).mapNotNull { correlation(rotate(local, size, it), remoteUpright) }.maxOrNull()

    /** The grid as hex bytes, for the cache. */
    fun encode(grid: DoubleArray): String =
        grid.joinToString("") { String.format(java.util.Locale.US, "%02x", it.toInt().coerceIn(0, 255)) }

    fun decode(value: String, size: Int = GRID): DoubleArray? {
        if (value.length != size * size * 2) return null
        return runCatching { DoubleArray(size * size) { value.substring(it * 2, it * 2 + 2).toInt(16).toDouble() } }.getOrNull()
    }

    /** Grey grid of an encoded image, decoded at a small size. */
    fun gridOf(open: () -> InputStream): DoubleArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 128) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = open().use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return greyGrid(pixels, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }
}
