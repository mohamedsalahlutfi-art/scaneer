package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Live pre-capture checks on an analysis frame with a detected page quad
 * (TL, TR, BR, BL). Auto-capture pauses while an issue is present; manual
 * capture still works. Thresholds are deliberately conservative: a missed
 * warning costs one review, a false alarm stalls the whole batch.
 *
 * - SHADOW: a large, smooth dark region on the page. Text, stamps and lines
 *   are removed first (morphological closing), so only illumination remains.
 * - LOW_CONTRAST: paper and the surface just outside its edges have almost
 *   the same brightness — the boundary is a guess.
 * - BLUR: the page has content but almost no fine detail (motion/focus).
 */
object CaptureQuality {

    enum class Issue(val message: String) {
        SHADOW("ظل على الورقة — حرّك الهاتف أو غيّر اتجاه الإضاءة"),
        LOW_CONTRAST("حدود الورقة غير واضحة — ضعها على سطح داكن"),
        BLUR("الصورة غير واضحة — ثبّت الهاتف"),
    }

    /** Never throws; null means "no issue found" (or the check could not run). */
    fun assess(frame: Bitmap, quad: FloatArray): Issue? =
        runCatching { assessOrThrow(frame, quad) }.getOrNull()

    private const val PAGE_W = 360
    private const val PAGE_H = 480

    /** Illumination below this fraction of the page's bright level counts as shadowed. */
    private const val SHADOW_RATIO = 0.60
    /** Shadowed share of the page that pauses auto-capture. */
    private const val SHADOW_MIN_FRAC = 0.16

    /** Median |inside − outside| gray difference along the edges below this = no contrast. */
    private const val CONTRAST_MIN = 9.0

    /** Laplacian variance below this on a page that has visible content = blurred. */
    private const val BLUR_LAPLACIAN_MIN = 10.0
    private const val BLUR_CONTENT_STD = 22.0

    private fun assessOrThrow(frame: Bitmap, quad: FloatArray): Issue? {
        if (quad.size != 8 || !OpenCVLoader.initLocal()) return null
        val rgba = Mat()
        val gray = Mat()
        val page = Mat()
        try {
            Utils.bitmapToMat(frame, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            // Portrait/landscape output follows the quad's own shape.
            val wTop = hypot((quad[2] - quad[0]).toDouble(), (quad[3] - quad[1]).toDouble())
            val hLeft = hypot((quad[6] - quad[0]).toDouble(), (quad[7] - quad[1]).toDouble())
            val (ow, oh) = if (wTop > hLeft) PAGE_H to PAGE_W else PAGE_W to PAGE_H
            val from = MatOfPoint2f(
                Point(quad[0].toDouble(), quad[1].toDouble()), Point(quad[2].toDouble(), quad[3].toDouble()),
                Point(quad[4].toDouble(), quad[5].toDouble()), Point(quad[6].toDouble(), quad[7].toDouble()),
            )
            val to = MatOfPoint2f(
                Point(0.0, 0.0), Point(ow - 1.0, 0.0), Point(ow - 1.0, oh - 1.0), Point(0.0, oh - 1.0),
            )
            val m = Imgproc.getPerspectiveTransform(from, to)
            Imgproc.warpPerspective(gray, page, m, Size(ow.toDouble(), oh.toDouble()), Imgproc.INTER_AREA)
            m.release(); from.release(); to.release()

            if (hasShadow(page)) return Issue.SHADOW
            if (edgeContrast(gray, quad) < CONTRAST_MIN) return Issue.LOW_CONTRAST
            if (isBlurred(page)) return Issue.BLUR
            return null
        } finally {
            rgba.release(); gray.release(); page.release()
        }
    }

    private fun hasShadow(page: Mat): Boolean {
        val illum = Mat()
        try {
            // Closing (dilate then erode) fills dark strokes up to ~10 px wide
            // with surrounding paper brightness; the blur leaves illumination.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(23.0, 23.0))
            Imgproc.morphologyEx(page, illum, Imgproc.MORPH_CLOSE, k)
            k.release()
            Imgproc.GaussianBlur(illum, illum, Size(0.0, 0.0), 9.0)
            // Ignore a 6% border: corner errors put desk pixels there.
            val mx = (illum.cols() * 0.06).toInt()
            val my = (illum.rows() * 0.06).toInt()
            val inner = illum.submat(my, illum.rows() - my, mx, illum.cols() - mx)
            val values = ByteArray((inner.total() * inner.channels()).toInt())
            inner.clone().also { it.get(0, 0, values); it.release() }
            inner.release()
            if (values.isEmpty()) return false
            val hist = IntArray(256)
            for (b in values) hist[b.toInt() and 0xFF]++
            val bright = percentile(hist, values.size, 0.90)
            if (bright < 70) return false // whole page dark: exposure, not a shadow
            val limit = bright * SHADOW_RATIO
            var dark = 0
            for (v in 0 until 256) if (v < limit) dark += hist[v]
            return dark.toDouble() / values.size >= SHADOW_MIN_FRAC
        } finally {
            illum.release()
        }
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = (total * p).toLong()
        var acc = 0L
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= target) return v
        }
        return 255
    }

    /** Median gray difference between points just inside and just outside each side. */
    private fun edgeContrast(gray: Mat, quad: FloatArray): Double {
        val w = gray.cols()
        val h = gray.rows()
        val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4.0
        val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4.0
        val d = max(4.0, hypot(w.toDouble(), h.toDouble()) * 0.012)
        val diffs = ArrayList<Double>(96)
        val px = ByteArray(1)
        fun at(x: Double, y: Double): Int? {
            val xi = x.toInt(); val yi = y.toInt()
            if (xi < 0 || yi < 0 || xi >= w || yi >= h) return null
            gray.get(yi, xi, px)
            return px[0].toInt() and 0xFF
        }
        for (side in 0 until 4) {
            val ax = quad[side * 2].toDouble(); val ay = quad[side * 2 + 1].toDouble()
            val bx = quad[(side * 2 + 2) % 8].toDouble(); val by = quad[(side * 2 + 3) % 8].toDouble()
            val len = hypot(bx - ax, by - ay)
            if (len < 1.0) continue
            var nx = -(by - ay) / len
            var ny = (bx - ax) / len
            // Point the normal away from the page centre.
            val mx = (ax + bx) / 2; val my = (ay + by) / 2
            if ((mx - cx) * nx + (my - cy) * ny < 0) { nx = -nx; ny = -ny }
            for (i in 1..22) {
                val t = 0.1 + 0.8 * i / 23.0
                val x = ax + (bx - ax) * t
                val y = ay + (by - ay) * t
                val inside = at(x - nx * d, y - ny * d) ?: continue
                val outside = at(x + nx * d, y + ny * d) ?: continue // page touches frame edge
                diffs.add(abs(inside - outside).toDouble())
            }
        }
        // Too few outside samples (page fills the frame): nothing to judge.
        if (diffs.size < 16) return Double.MAX_VALUE
        diffs.sort()
        return diffs[diffs.size / 2]
    }

    private fun isBlurred(page: Mat): Boolean {
        val lap = Mat()
        val mean = MatOfDouble()
        val std = MatOfDouble()
        try {
            Core.meanStdDev(page, mean, std)
            val contentStd = std.toArray().firstOrNull() ?: return false
            if (contentStd < BLUR_CONTENT_STD) return false // blank page: nothing to judge
            Imgproc.Laplacian(page, lap, CvType.CV_64F)
            Core.meanStdDev(lap, mean, std)
            val sd = std.toArray().firstOrNull() ?: return false
            return sd * sd < BLUR_LAPLACIAN_MIN
        } finally {
            lap.release(); mean.release(); std.release()
        }
    }
}
