package io.github.alexeygrigorev.openscan.scan

import java.io.OutputStream
import java.util.Locale

/**
 * Minimal streaming PDF writer for scanned pages: every page is one JPEG
 * embedded as-is with /DCTDecode (exactly how office scanners do it), so the
 * file stays small and the page is never re-encoded by the PDF layer.
 * Android's PdfDocument stored page bitmaps losslessly (Flate), which made
 * a 16-page scan ~23 MB instead of ~6 MB.
 *
 * Streaming: pages are written as they arrive; only offsets stay in memory,
 * so hundreds of pages export without holding all JPEGs at once.
 */
class JpegPdfWriter(out: OutputStream) : AutoCloseable {

    private val sink = CountingStream(out)
    private val offsets = HashMap<Int, Long>()
    private val pageIds = ArrayList<Int>()
    private var nextId = 3 // 1 = catalog, 2 = page tree
    private var finished = false

    init {
        write("%PDF-1.4\n%âãÏÓ\n")
    }

    /**
     * Adds one page of [pageWidth] x [pageHeight] points showing the JPEG
     * ([pixelWidth] x [pixelHeight], RGB or gray) at the given placement.
     */
    fun addPage(
        jpeg: ByteArray,
        pixelWidth: Int,
        pixelHeight: Int,
        gray: Boolean,
        pageWidth: Float,
        pageHeight: Float,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
    ) {
        check(!finished) { "already finished" }
        val imageId = nextId++
        val contentId = nextId++
        val pageId = nextId++

        begin(imageId)
        write(
            "<< /Type /XObject /Subtype /Image /Width $pixelWidth /Height $pixelHeight " +
                "/ColorSpace /${if (gray) "DeviceGray" else "DeviceRGB"} /BitsPerComponent 8 " +
                "/Filter /DCTDecode /Length ${jpeg.size} >>\nstream\n"
        )
        sink.write(jpeg)
        write("\nendstream\nendobj\n")

        // PDF origin is bottom-left; the placement is given top-left.
        val content = "q ${n(width)} 0 0 ${n(height)} ${n(x)} ${n(pageHeight - y - height)} cm /Im0 Do Q\n"
        val bytes = content.toByteArray(Charsets.US_ASCII)
        begin(contentId)
        write("<< /Length ${bytes.size} >>\nstream\n")
        sink.write(bytes)
        write("\nendstream\nendobj\n")

        begin(pageId)
        write(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(pageWidth)} ${n(pageHeight)}] " +
                "/Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $contentId 0 R >>\nendobj\n"
        )
        pageIds.add(pageId)
    }

    /** Writes the page tree, catalog, xref and trailer. */
    fun finish() {
        if (finished) return
        finished = true
        begin(2)
        write("<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pageIds.size} >>\nendobj\n")
        begin(1)
        write("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        val xref = sink.count
        val size = nextId
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (id in 1 until size) {
            sb.append(String.format(Locale.ROOT, "%010d 00000 n \n", offsets[id] ?: 0L))
        }
        sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        write(sb.toString())
        sink.flush()
    }

    val pageCount: Int get() = pageIds.size

    override fun close() = finish()

    private fun begin(id: Int) {
        offsets[id] = sink.count
        write("$id 0 obj\n")
    }

    private fun write(s: String) = sink.write(s.toByteArray(Charsets.ISO_8859_1))

    private fun n(v: Float): String = String.format(Locale.ROOT, "%.2f", v)

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b); count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len); count += len
        }

        override fun flush() = out.flush()
    }
}
