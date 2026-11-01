package io.github.alexeygrigorev.openscan.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DocumentDuplicateDetectorTest {
    private fun sample(): IntArray = IntArray(64 * 64) { i ->
        val row = i / 64
        val col = i % 64
        if (row in 12..49 && row % 7 <= 2 && col in 8..52) 25 else 247
    }

    @Test fun identicalPageProducesWarning() {
        val first = DocumentDuplicateDetector.fromLuma(sample())
        val match = DocumentDuplicateDetector.findCandidate(first, listOf(5 to first))
        assertEquals(5, match?.pageNumber)
    }

    @Test fun blankSheetIsNotAssumedDuplicate() {
        val blank = DocumentDuplicateDetector.fromLuma(IntArray(64 * 64) { 250 })
        assertNull(DocumentDuplicateDetector.findCandidate(blank, listOf(1 to blank)))
    }

    @Test fun alteredContentIsNotDiscarded() {
        val first = DocumentDuplicateDetector.fromLuma(sample())
        val modified = sample()
        for (row in 23..40) for (col in 10..53) modified[row * 64 + col] = 0
        val changed = DocumentDuplicateDetector.fromLuma(modified)
        assertNull(DocumentDuplicateDetector.findCandidate(changed, listOf(1 to first)))
    }
}
