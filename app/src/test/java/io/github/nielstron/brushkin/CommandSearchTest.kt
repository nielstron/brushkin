package io.github.nielstron.brushkin

import io.github.nielstron.brushkin.ui.Cmd
import io.github.nielstron.brushkin.ui.CommandSearch
import io.github.nielstron.brushkin.ui.KEYWORDS
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSearchTest {
    private val cmds = listOf(
        "File" to "Export as PNG", "File" to "Share image…", "Edit" to "Undo", "Edit" to "Clear",
        "Select" to "None", "Select" to "Invert", "Select" to "Grow…", "Select" to "Shrink…", "Select" to "Select foreground (AI)",
        "Image" to "Scale image…", "Image" to "Crop to selection", "Image" to "Rotate 90° clockwise",
        "Colors" to "Curves…", "Colors" to "Levels…", "Colors" to "Brightness-Contrast…", "Colors" to "Desaturate",
        "Filters" to "Gaussian Blur…", "Filters" to "Unsharp Mask…", "Tools" to "Heal", "Tools" to "Clone", "Tools" to "Text",
    ).map { (s, l) -> Cmd(s, l, run = {}) }
    private val search = CommandSearch(cmds)

    private fun top(q: String, n: Int = 3) = search.search(q).take(n).map { it.label }

    @Test fun `intent queries find the right commands`() {
        assertTrue(top("make it brighter").toString(), top("make it brighter").any { it.startsWith("Brightness") || it.startsWith("Curves") || it.startsWith("Levels") })
        assertTrue(top("remove background").toString(), "Select foreground (AI)" in top("remove background"))
        assertTrue(top("black and white").toString(), "Desaturate" in top("black and white"))
        assertTrue(top("resize").toString(), "Scale image…" in top("resize"))
        assertTrue(top("fix pimple blemish").toString(), "Heal" in top("fix pimple blemish"))
        assertTrue(top("sharpen").toString(), "Unsharp Mask…" in top("sharpen"))
    }

    @Test fun `labels and partial words still work`() {
        assertTrue(top("select none").toString(), top("select none").first() == "None")
        assertTrue(top("gaus").toString(), top("gaus").first() == "Gaussian Blur…")
        assertTrue(top("cur").toString(), top("cur").first() == "Curves…")
    }

    @Test fun `keywords exist for commands`() { assertTrue(KEYWORDS.size > 30) }
}
