package io.github.junwen853.cloudtransfer

import io.github.junwen853.cloudtransfer.icloud.RemoteItem
import io.github.junwen853.cloudtransfer.media.MediaFormats
import io.github.junwen853.cloudtransfer.media.SamePictureFinder
import io.github.junwen853.cloudtransfer.media.VisualHash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MediaRulesTest {

    @Test
    fun formatsIcloudAcceptsPass() {
        listOf("IMG_1.JPG", "IMG_2.heic", "Screenshot.png", "a.webp", "scan.tiff", "RAW_1.dng", "VID.mp4", "clip.MOV", "x.m4v")
            .forEach { assertEquals(it, "", MediaFormats.blockReason(it, "", 5_000_000, it.endsWith("mp4") || it.endsWith("MOV") || it.endsWith("m4v"))) }
        // Motion photos are single JPEG/HEIC files with the clip inside: they upload as photos.
        assertEquals("", MediaFormats.blockReason("MVIMG_20260101_120000.jpg", "image/jpeg", 6_000_000, false))
    }

    @Test
    fun formatsIcloudRefusesAreBlocked() {
        assertTrue(MediaFormats.blockReason("anim.gif", "image/gif", 100_000, false).contains("GIF"))
        assertTrue(MediaFormats.blockReason("clip.3gp", "video/3gpp", 100_000, true).contains("MP4/MOV/M4V"))
        assertTrue(MediaFormats.blockReason("clip.mkv", "video/x-matroska", 100_000, true).contains("MP4/MOV/M4V"))
        assertTrue(MediaFormats.blockReason("big.mp4", "video/mp4", 500L * 1024 * 1024, true).contains("500 MB"))
        assertTrue(MediaFormats.blockReason("tiny.jpg", "image/jpeg", 100, false).isNotEmpty())
        assertTrue(MediaFormats.blockReason("pic.avif", "image/avif", 100_000, false).contains("AVIF"))
        assertTrue(MediaFormats.blockReason("a".repeat(252) + ".jpg", "image/jpeg", 100_000, false).isNotEmpty())
    }

    private fun picture(width: Int, height: Int, phase: Double = 0.0): IntArray = IntArray(width * height) { index ->
        val x = (index % width).toDouble() / width
        val y = (index / width).toDouble() / height
        val value = (128 + 90 * Math.sin(6.0 * x + phase) * Math.cos(4.0 * y - phase) + 30 * x).toInt().coerceIn(0, 255)
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
    }

    @Test
    fun samePictureCorrelatesAcrossSizesAndTurns() {
        val big = VisualHash.greyGrid(picture(400, 300), 400, 300)
        val small = VisualHash.greyGrid(picture(200, 150), 200, 150)
        val resized = VisualHash.bestCorrelation(big, small)!!
        assertTrue("resized $resized", resized >= VisualHash.COPY_MIN_CORRELATION)
        val turned = VisualHash.bestCorrelation(big, VisualHash.rotate(big, VisualHash.GRID, 1))!!
        assertTrue("turned $turned", turned >= VisualHash.COPY_MIN_CORRELATION)
        val other = VisualHash.bestCorrelation(big, VisualHash.greyGrid(picture(400, 300, phase = 2.0), 400, 300))!!
        assertTrue("other $other", other < VisualHash.SAME_PICTURE_MIN_CORRELATION)
        val decoded = VisualHash.decode(VisualHash.encode(big))!!
        assertTrue(VisualHash.correlation(big, decoded)!! > 0.999)
    }

    /** Colour bars: flat bands with hard edges that resampling smears. */
    private fun bars(width: Int, height: Int, noise: Int, shift: Int = 0): IntArray {
        val levels = intArrayOf(76, 150, 226, 29, 105, 179)
        val random = java.util.Random(noise.toLong())
        return IntArray(width * height) { index ->
            val x = (index % width + shift).coerceIn(0, width - 1)
            val value = (levels[x * levels.size / width] + if (noise > 0) random.nextInt(2 * noise + 1) - noise else 0).coerceIn(0, 255)
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
    }

    @Test
    fun flatAreasAndEdgesDoNotBreakTheComparison() {
        val original = VisualHash.greyGrid(bars(2000, 1500, 0), 2000, 1500)
        val thumbnail = VisualHash.greyGrid(bars(320, 240, 3, shift = 2), 320, 240)
        assertTrue(VisualHash.bestCorrelation(original, thumbnail)!! >= VisualHash.COPY_MIN_CORRELATION)
        val blank = IntArray(100 * 100) { (0xff shl 24) or 0x808080 }
        assertEquals(null, VisualHash.bestCorrelation(original, VisualHash.greyGrid(blank, 100, 100)))
    }

    @Test
    fun burstFramesAreNotTheSameMoment() {
        assertTrue(SamePictureFinder.sameMoment(1_790_764_215_123, 1_790_764_215_123))
        assertFalse(SamePictureFinder.sameMoment(1_790_764_215_123, 1_790_764_215_223))
        assertTrue(SamePictureFinder.sameMoment(1_790_764_215_000, 1_790_764_215_640))
        assertFalse(SamePictureFinder.sameMoment(1_790_764_215_000, 1_790_764_216_200))
    }

    @Test
    fun captureTimeAndShapeRules() {
        assertEquals(LocalDateTime.of(2026, 9, 30, 18, 30, 15), SamePictureFinder.parseExifTime("2026:09:30 18:30:15"))
        assertEquals(null, SamePictureFinder.parseExifTime("garbage"))
        assertTrue(SamePictureFinder.sameShape(4032, 3024, 3024, 4032))
        assertTrue(SamePictureFinder.sameShape(1280, 960, 4032, 3024))
        assertFalse(SamePictureFinder.sameShape(1920, 1080, 4032, 3024))
        assertTrue(SamePictureFinder.sameShape(0, 0, 4032, 3024))
        val item = RemoteItem("i", "PrimarySync", "M", "c", 1, "", 0, "a.jpg", 1_000_000L, 28800, 1, 1, false)
        assertEquals(listOf(1_000_000L + 28_800_000L, 1_000_000L - 28_800_000L), SamePictureFinder.remoteWallTimes(item))
        val minutes = item.copy(timeZoneOffsetSeconds = -480)
        assertTrue(SamePictureFinder.remoteWallTimes(minutes).contains(1_000_000L + 28_800_000L))
        assertEquals(emptyList<Long>(), SamePictureFinder.remoteWallTimes(item.copy(timeZoneOffsetSeconds = null)))
    }
}
