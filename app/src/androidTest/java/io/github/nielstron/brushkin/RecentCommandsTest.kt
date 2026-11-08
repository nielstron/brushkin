package io.github.nielstron.brushkin

import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.ui.Cmd
import io.github.nielstron.brushkin.ui.RecentCommands
import org.junit.Assert.*
import org.junit.Test

class RecentCommandsTest {
    @Test fun recentCommandsPersistDeduplicateAndUseCurrentHandlers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("recent-commands-test", 0)
        prefs.edit().clear().commit()
        try {
            val history = RecentCommands(prefs)
            val old = (0..7).map { Cmd("Filters", "Filter $it", run = { error("Stale handler") }) }
            old.forEach(history::record)
            history.record(old[4])
            var selected = -1
            val live = (0..7).map { index -> Cmd("Filters", "Filter $index", run = { selected = index }) }
            val restored = RecentCommands(prefs).resolve(live)
            assertEquals(listOf("Filter 4", "Filter 7", "Filter 6", "Filter 5", "Filter 3", "Filter 2"), restored.map { it.label })
            restored.first().run()
            assertEquals(4, selected)
            assertEquals(5, history.resolve(live.filter { it.label != "Filter 4" }).size)
            assertTrue(history.resolve(listOf(Cmd("Tools", "Filter 4", run = {}))).isEmpty())
        } finally { prefs.edit().clear().commit() }
    }
}
