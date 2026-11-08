package io.github.nielstron.brushkin

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.EditorViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StartupRestoreTest {
    private class TestApp(context: Context, private val directory: File) : Application() {
        init { attachBaseContext(context) }
        override fun getFilesDir() = directory
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("startup-test-$name",mode)
    }
    @Test fun loadingEndsOnlyAfterTheSavedImageIsInstalled() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"startup-restore-test").apply { mkdirs() }
        val saved=File(dir,"autosave.ora")
        val layer=Layer(name="Saved",pixels=TiledBitmap.fromPixels(IntArray(64*48) { 0xff508090.toInt() },64,48))
        saved.outputStream().use { Ora.write(Stack(64,48,listOf(layer),layer.id),it) }
        val original=saved.readBytes()
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main) { EditorViewModel(TestApp(context,dir)).also { store.put("test",it) } }
        try {
            assertTrue(vm.startupLoading)
            vm.engine.post {}.join()
            vm.autosave()
            assertArrayEquals("Don't overwrite the saved image with the initial blank document",original,saved.readBytes())
            withContext(Dispatchers.Main) { vm.restoreAtStartup() }.join()
            assertFalse(vm.startupLoading)
            val restored=vm.engine.snapshot()
            assertEquals(64,restored.width)
            assertEquals(48,restored.height)
            assertEquals(0xff508090.toInt(),restored.active!!.pixels.toPixels()[0])
        } finally { withContext(Dispatchers.Main) { store.clear() }; dir.deleteRecursively() }
    }
}
