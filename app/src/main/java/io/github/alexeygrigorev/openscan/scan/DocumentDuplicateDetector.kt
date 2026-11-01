package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A conservative *warning*, not a verdict: forms with identical layouts may be
 * distinct. Never silently delete or replace a candidate, and always offer Keep.
 *
 * The two directional hashes describe layout; the 8x8 ink histogram guards
 * against false positives from different documents with similar white margins.
 * Image decoding and hashing are bounded to 64x64 pixels per captured page.
 */
data class DocumentFingerprint(
    val horizontal: Long,
    val vertical: Long,
    val inkGrid: IntArray,
    val inkPermille: Int,
)

data class DuplicateMatch(val pageNumber: Int, val distance: Int)

object DocumentDuplicateDetector {
    const val SAMPLE_SIZE = 64

    /** Pure JVM-computable path; [luma] is a row-major 64 x 64 grayscale image. */
    fun fromLuma(luma: IntArray): DocumentFingerprint {
        require(luma.size == SAMPLE_SIZE * SAMPLE_SIZE)
        fun sample(x: Int, y: Int, xSteps: Int, ySteps: Int): Int =
            luma[(y * (SAMPLE_SIZE - 1) / ySteps) * SAMPLE_SIZE + (x * (SAMPLE_SIZE - 1) / xSteps)]
        var horizontal = 0L
        var vertical = 0L
        for (y in 0..7) for (x in 0..7) {
            horizontal = (horizontal shl 1) or
                if (sample(x, y, 8, 7) > sample(x + 1, y, 8, 7)) 1L else 0L
            vertical = (vertical shl 1) or
                if (sample(x, y, 7, 8) > sample(x, y + 1, 7, 8)) 1L else 0L
        }
        val mean = luma.average().roundToInt()
        val threshold = (mean - 44).coerceIn(100, 205)
        val grid = IntArray(64)
        var ink = 0
        luma.forEachIndexed { i, value ->
            if (value < threshold) {
                ink++
                grid[(i / SAMPLE_SIZE / 8) * 8 + (i % SAMPLE_SIZE / 8)]++
            }
        }
        return DocumentFingerprint(horizontal, vertical, grid, ink * 1000 / luma.size)
    }

    /** Only flags strong similarities; with blank sheets, don't guess. */
    fun findCandidate(
        incoming: DocumentFingerprint,
        existing: List<Pair<Int, DocumentFingerprint>>,
    ): DuplicateMatch? {
        if (incoming.inkPermille !in 12..740) return null
        return existing.mapNotNull { (number, prior) ->
            if (prior.inkPermille !in 12..740) return@mapNotNull null
            if (abs(incoming.inkPermille - prior.inkPermille) > 22) return@mapNotNull null
            val h = java.lang.Long.bitCount(incoming.horizontal xor prior.horizontal)
            val v = java.lang.Long.bitCount(incoming.vertical xor prior.vertical)
            val distance = h + v
            if (h > 7 || v > 7 || distance > 11) return@mapNotNull null
            val histogramError = incoming.inkGrid.indices.sumOf { i ->
                abs(incoming.inkGrid[i] - prior.inkGrid[i])
            }
            if (histogramError > 240) return@mapNotNull null
            DuplicateMatch(number, distance)
        }.minByOrNull { it.distance }
    }

    /**
     * Create a normalized fingerprint from the perspective-corrected page.
     * Fail open (no warning) if a device cannot crop/decode the frame.
     */
    fun fromCameraBitmap(frame: Bitmap, quad: FloatArray?): DocumentFingerprint? {
        var preview: Bitmap? = null
        var sheet: Bitmap? = null
        var tiny: Bitmap? = null
        return try {
            val scale = min(1f, 850f / max(frame.width, frame.height))
            preview = Bitmap.createScaledBitmap(
                frame,
                max(1, (frame.width * scale).roundToInt()),
                max(1, (frame.height * scale).roundToInt()), true,
            )
            val reducedQuad = quad?.takeIf { it.size == 8 }?.let {
                FloatArray(8) { i -> it[i] * if (i % 2 == 0) preview!!.width.toFloat() / frame.width
                    else preview!!.height.toFloat() / frame.height }
            }
            sheet = reducedQuad?.let { runCatching { ScanPipeline.crop(preview!!, it) }.getOrNull() }
                ?: preview
            tiny = Bitmap.createScaledBitmap(sheet!!, SAMPLE_SIZE, SAMPLE_SIZE, true)
            val pixels = IntArray(SAMPLE_SIZE * SAMPLE_SIZE)
            tiny!!.getPixels(pixels, 0, SAMPLE_SIZE, 0, 0, SAMPLE_SIZE, SAMPLE_SIZE)
            val luma = IntArray(pixels.size) { i ->
                val color = pixels[i]
                ((color shr 16 and 255) * 299 + (color shr 8 and 255) * 587 +
                    (color and 255) * 114) / 1000
            }
            fromLuma(luma)
        } catch (_: Throwable) {
            null
        } finally {
            if (tiny !== sheet && tiny !== preview && tiny !== frame) tiny?.recycle()
            if (sheet !== preview && sheet !== frame) sheet?.recycle()
            if (preview !== frame) preview?.recycle()
        }
    }
}
