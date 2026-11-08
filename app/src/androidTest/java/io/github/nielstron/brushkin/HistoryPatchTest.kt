package io.github.nielstron.brushkin

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.IntBuffer

/** Undo must restore the exact bitmap memory, down to semi-transparent pixels. */
@RunWith(AndroidJUnit4::class)
class HistoryPatchTest {
    private val rawSize = TILE * TILE
    private fun raw(b: Bitmap?) = IntArray(rawSize).also { b!!.copyPixelsToBuffer(IntBuffer.wrap(it)) }
    private fun noisy(seed: Int) = TiledBitmap.newTile().apply {
        val r = java.util.Random(seed.toLong())
        setPixels(IntArray(rawSize) { (r.nextInt(256) shl 24) or r.nextInt(0xFFFFFF) }, 0, TILE, 0, 0, TILE, TILE)
    }
    private fun painted(tile: Bitmap, x: Int, y: Int, w: Int, h: Int, color: Int) = tile.copy(Bitmap.Config.ARGB_8888, true).apply { setPixels(IntArray(w * h) { color }, 0, w, x, y, w, h) }
    private fun stack(t: TiledBitmap) = Layer(name = "L", pixels = t).let { Stack(TILE, TILE, listOf(it), it.id) }

    @Test fun aSeriesOfSmallStrokesUndoesAndRedoesExactly() {
        val target = TiledBitmap(TILE, TILE)
        val s = stack(target)
        val versions = arrayListOf(noisy(1))
        target.tiles[0] = versions[0]
        val history = History()
        val r = java.util.Random(2)
        repeat(30) {
            val previous = versions.last()
            val next = painted(previous, r.nextInt(200), r.nextInt(200), 20, 20, (128 shl 24) or r.nextInt(0xFFFFFF))
            versions.add(next); target.tiles[0] = next
            history.push(HistoryEntry("s$it", s, s, listOf(TileDiff(target, mapOf(0 to (previous to next))))))
        }
        for (i in 29 downTo 0) { history.undo(); assertArrayEquals("undo to $i", raw(versions[i]), raw(target.tiles[0])) }
        for (i in 1..30) { history.redo(); assertArrayEquals("redo to $i", raw(versions[i]), raw(target.tiles[0])) }
    }

    @Test fun aSmallStrokeCostsItsAreaNotWholeTiles() {
        val target = TiledBitmap(TILE, TILE)
        val before = noisy(1); val after = painted(before, 10, 10, 20, 20, -1)
        target.tiles[0] = after
        val entry = HistoryEntry("s", stack(target), stack(target), listOf(TileDiff(target, mapOf(0 to (before to after)))))
        val whole = entry.bytes
        History().push(entry)
        assertTrue("${entry.bytes} of $whole", entry.bytes < whole / 50)
    }

    @Test fun aChangeOfMostOfATileKeepsWholeTiles() {
        val target = TiledBitmap(TILE, TILE)
        val before = noisy(1); val after = noisy(2)
        target.tiles[0] = after
        val diff = TileDiff(target, mapOf(0 to (before to after)))
        History().push(HistoryEntry("f", stack(target), stack(target), listOf(diff)))
        assertTrue(diff.patches.isEmpty())
        diff.undo(); assertSame(before, target.tiles[0])
    }

    @Test fun missingTilesStayMissing() {
        for (fill in listOf(0, MASK_WHITE, MASK_BLACK)) {
            val target = TiledBitmap(TILE, TILE, fill)
            val after = TiledBitmap.newTile().apply { eraseColor(fill) }.let { painted(it, 5, 5, 4, 4, 0xff336699.toInt()) }
            target.tiles[0] = after
            val diff = TileDiff(target, mapOf(0 to (null to after)))
            History().push(HistoryEntry("s", stack(target), stack(target), listOf(diff)))
            assertEquals(1, diff.patches.size)
            val rawAfter = raw(after)
            diff.undo(); assertNull(target.tiles[0])
            diff.redo(); assertArrayEquals(rawAfter, raw(target.tiles[0]))
            // And back again, from the rebuilt tile.
            diff.undo(); assertNull(target.tiles[0])
        }
    }

    @Test fun patchesSurviveSavingAndRestoring() {
        val target = TiledBitmap(TILE, TILE)
        val s = stack(target)
        val before = noisy(1); val after = painted(before, 30, 40, 16, 16, (90 shl 24) or 0x336699)
        target.tiles[0] = after
        val history = History(); history.push(HistoryEntry("s", s, s, listOf(TileDiff(target, mapOf(0 to (before to after))))))
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "patch-test.history")
        HistoryStore.write(HistoryStore.capture(s, history, 4)!!, file, 1, 2)
        val back = HistoryStore.read(file, 1, 2, "D")!!
        file.delete()
        assertArrayEquals(raw(after), raw(back.stack.layers[0].pixels.tiles[0]))
        back.history.undo()
        assertArrayEquals(raw(before), raw(back.stack.layers[0].pixels.tiles[0]))
    }

    /** Runs on the engine thread after each edit, so it has to be quick. */
    @Test fun compactingIsFastEnoughForAnEdit() {
        val target = TiledBitmap(TILE, TILE)
        val before = noisy(1); val after = painted(before, 1, 1, 8, 8, -1)
        val start = System.nanoTime()
        repeat(50) { TileDiff(target, mapOf(0 to (before to after))).compact() }
        val perTileMs = (System.nanoTime() - start) / 1e6 / 50
        android.util.Log.i("HistoryPatch", "compact per tile ms=$perTileMs")
        assertTrue("$perTileMs ms per tile", perTileMs < 10)
    }
}
