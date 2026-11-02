package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.JpegPdfWriter
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JpegPdfWriterTest {

    private fun jpeg(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        return ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
    }

    @Test
    fun `writes a structurally valid multi-page pdf with embedded jpegs`() {
        val a = jpeg(40, 60)
        val b = jpeg(80, 30)
        val out = ByteArrayOutputStream()
        JpegPdfWriter(out).use { w ->
            w.addPage(a, 40, 60, false, 595f, 842f, 10f, 10f, 575f, 822f)
            w.addPage(b, 80, 30, false, 842f, 595f, 0f, 0f, 842f, 316f)
            assertEquals(2, w.pageCount)
        }
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertEquals(2, Regex("/DCTDecode").findAll(text).count())
        // startxref must point at the xref table.
        val xrefAt = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        assertTrue(text.startsWith("xref", xrefAt))
        // Every xref offset must point at "<id> 0 obj".
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        entries.forEachIndexed { i, line ->
            val off = line.substring(0, 10).toInt()
            assertTrue("object ${i + 1}", text.startsWith("${i + 1} 0 obj", off))
        }
        // The JPEG bytes are embedded verbatim.
        assertTrue(text.contains(String(a, Charsets.ISO_8859_1)))
    }
}
