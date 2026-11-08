package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.engine.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/** GEGL rejects these settings, as it would a damaged file's. */
class FilterFailureTest {
    private fun rejected() = GeglFilter("Bad", "gegl:gaussian-blur", mapOf("std-dev-x" to 99999.0))

    @Test fun aRejectedEffectIsSkippedAndTheRestStillApply() {
        val previous = Effects.factory; val previousSkipped = Effects.onSkipped
        val messages = CopyOnWriteArrayList<String>()
        Effects.factory = { if (it.name == "Bad One") rejected() else ExposureFilter(1f, 0f) }
        Effects.onSkipped = { messages.add(it) }
        try {
            val px = IntArray(64 * 64) { 0xff506070.toInt() }
            val layer = Layer(name = "L", pixels = TiledBitmap.fromPixels(px, 64, 64),
                effects = listOf(LayerEffect("Bad One", emptyList()), LayerEffect("Exposure", emptyList())))
            val expected = ExposureFilter(1f, 0f).apply(px, 64, 64)
            assertArrayEquals(expected, Effects.pixelsSync(layer).toPixels())
            assertEquals(listOf("Skipped the Bad One effect: its settings can't be used"), messages)
        } finally { Effects.factory = previous; Effects.onSkipped = previousSkipped }
    }

    @Test fun aRejectedPreviewReportsOnceAndKeepsWorking() = runBlocking {
        val e = Engine(); e.newDocument(64, 64, -1).join()
        val messages = CopyOnWriteArrayList<String>()
        e.onMessage = { messages.add(it) }
        e.filterBegin(false, false).join()
        suspend fun until(n: Int) = withTimeout(10000) { while (messages.size < n) delay(20) }
        e.filterPreview(rejected()); until(1)
        e.filterPreview(rejected()); delay(300)
        assertEquals("One message for a run of failures", 1, messages.size)
        e.filterPreview(ExposureFilter(1f, 0f)); delay(500)
        e.filterPreview(rejected()); until(2)
        assertEquals("Bad can't use these settings", messages.last())
        e.filterCancel().join(); e.shutdown()
    }

    @Test fun aRejectedApplyLeavesTheImageAlone() = runBlocking {
        val e = Engine(); e.newDocument(64, 64, -1).join()
        val messages = CopyOnWriteArrayList<String>()
        e.onMessage = { messages.add(it) }
        val before = e.snapshot().layers[0].pixels.toPixels()
        e.filterBegin(false, false).join()
        e.filterApply(rejected()).join()
        assertArrayEquals(before, e.snapshot().layers[0].pixels.toPixels())
        assertEquals(listOf("Couldn't apply Bad with these settings"), messages.toList())
        e.shutdown()
    }
}
