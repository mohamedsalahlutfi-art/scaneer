package io.github.alexeygrigorev.openscan.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.min

/**
 * Builds multi-page PDFs from page JPEGs with the JpegPdfWriter (JPEG pages, DCTDecode).
 * Pages are placed centered on an A4 sheet (595×842 pt, swapped for
 * landscape scans) — and, as everywhere in OpenScan, nothing is ever stamped
 * on top of them.
 *
 * Resolution (item 10): pages decode at up to 3600 px so a full-bleed A4
 * embeds at ~300 DPI (3600 px / 11.69 in); the old 2400 px ceiling topped
 * out near 200 DPI.
 */
object PdfExporter {

    /** Centered, aspect-preserving placement of an image on a page. Pure math — unit-tested. */
    data class Fit(val x: Float, val y: Float, val width: Float, val height: Float)

    fun fitInto(imageWidth: Int, imageHeight: Int, pageWidth: Float, pageHeight: Float): Fit {
        val scale = min(pageWidth / imageWidth, pageHeight / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return Fit(
            x = (pageWidth - width) / 2f,
            y = (pageHeight - height) / 2f,
            width = width,
            height = height,
        )
    }

    /** Decode bound for PDF pages: covers 300 DPI on A4's long edge. */
    const val PDF_MAX_DIM = 3600

    fun exportPdf(pageFiles: List<File>, out: OutputStream) {
        exportPdf(pageFiles, List(pageFiles.size) { ScanFilter.ORIGINAL }, out)
    }

    /**
     * Filtered export (item 6): each page is enhanced with its effective
     * filter before embedding. A filter that fails (e.g. OOM) falls back to
     * the original page — export never crashes on enhancement.
     */
    fun exportPdf(pageFiles: List<File>, filters: List<ScanFilter>, out: OutputStream) {
        val writer = JpegPdfWriter(out)
        pageFiles.forEachIndexed { index, file ->
            val filter = filters.getOrElse(index) { ScanFilter.ORIGINAL }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@forEachIndexed
            val fitsAsIs = maxOf(bounds.outWidth, bounds.outHeight) <= PDF_MAX_DIM

            val jpeg: ByteArray
            val pw: Int
            val ph: Int
            if (filter == ScanFilter.ORIGINAL && fitsAsIs && bounds.outMimeType == "image/jpeg") {
                // Untouched page: embed the stored JPEG bytes, zero re-encoding.
                jpeg = file.readBytes()
                pw = bounds.outWidth
                ph = bounds.outHeight
            } else {
                val decoded = Images.decodeScaled(file, maxDim = PDF_MAX_DIM) ?: return@forEachIndexed
                val bitmap = if (filter == ScanFilter.ORIGINAL) {
                    decoded
                } else {
                    ScanFilters.apply(decoded, filter)?.also { decoded.recycle() } ?: decoded
                }
                jpeg = ByteArrayOutputStream(bitmap.width * bitmap.height / 4).use { buf ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buf)
                    buf.toByteArray()
                }
                pw = bitmap.width
                ph = bitmap.height
                bitmap.recycle()
            }

            val landscape = pw > ph
            val pageWidth = (if (landscape) A4_LONG_PT else A4_SHORT_PT).toFloat()
            val pageHeight = (if (landscape) A4_SHORT_PT else A4_LONG_PT).toFloat()
            val fit = fitInto(pw, ph, pageWidth, pageHeight)
            writer.addPage(jpeg, pw, ph, gray = false, pageWidth, pageHeight, fit.x, fit.y, fit.width, fit.height)
        }
        writer.finish()
    }

    /** High Quality default: visually lossless for text, stamps and signatures. */
    const val JPEG_QUALITY = 90

    const val A4_SHORT_PT = 595
    const val A4_LONG_PT = 842
}
