package io.github.alexeygrigorev.openscan

import io.github.alexeygrigorev.openscan.scan.CaptureSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureSpeedTest {

    @Test
    fun `unknown or missing id falls back to balanced`() {
        assertEquals(CaptureSpeed.BALANCED, CaptureSpeed.fromId(null))
        assertEquals(CaptureSpeed.BALANCED, CaptureSpeed.fromId("warp-speed"))
    }

    @Test
    fun `ids round trip`() {
        for (speed in CaptureSpeed.entries) assertEquals(speed, CaptureSpeed.fromId(speed.id))
    }

    @Test
    fun `turbo is faster and quality is steadier than balanced`() {
        val t = CaptureSpeed.TURBO
        val b = CaptureSpeed.BALANCED
        val q = CaptureSpeed.QUALITY
        assertTrue(t.cooldownMs < b.cooldownMs && b.cooldownMs < q.cooldownMs)
        assertTrue(t.stableFrames <= b.stableFrames && b.stableFrames < q.stableFrames)
        assertTrue(t.saveMaxDim < b.saveMaxDim && b.saveMaxDim < q.saveMaxDim)
        assertEquals(q.saveMaxDim, CaptureSpeed.MAX_SAVE_DIM)
    }
}
