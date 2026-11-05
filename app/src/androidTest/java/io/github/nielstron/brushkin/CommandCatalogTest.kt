package io.github.nielstron.brushkin

import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.*
import io.github.nielstron.brushkin.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class CommandCatalogTest {
    // Keep ViewModel autosave/resources/preferences separate from the user's emulator document.
    private class TestApp(context: Context, val dir: File) : Application() {
        init { attachBaseContext(context) }
        override fun getFilesDir() = dir
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("command-test-$name",mode)
    }

    @Test fun panelActionsAreSearchableAndKeepTheirHandlers() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val dir=File(context.cacheDir,"command-catalog-test").apply{mkdirs()}
        val store=ViewModelStore()
        val vm=withContext(Dispatchers.Main){EditorViewModel(TestApp(context,dir)).also{store.put("test",it)}}
        val fileCalls=mutableListOf<String>()
        val files=Proxy.newProxyInstance(FileActions::class.java.classLoader,arrayOf(FileActions::class.java)){_,method,_->fileCalls.add(method.name);null} as FileActions
        try {
            val pixels=TiledBitmap.fromPixels(IntArray(48){Color.RED},8,6)
            val layer=Layer(name="Sample",pixels=pixels,mask=newMask(8,6),effects=listOf(LayerEffect("Gaussian Blur",listOf(1f))))
            val channel=SavedChannel(name="Outline",mask=TiledBitmap.fromPixels(IntArray(48){if(it==9)Color.WHITE else Color.BLACK},8,6))
            vm.engine.openStack(Stack(8,6,listOf(layer),layer.id,channels=listOf(channel)),"test").join()
            fun catalog()=commands(vm,vm.engine.info.value,files){null}
            fun command(query: String,label: String):Cmd {
                val hits=CommandSearch(catalog()).search(query).take(5)
                return hits.firstOrNull{it.label==label} ?: error("Missing $label for '$query': ${hits.map{it.label}}")
            }
            withContext(Dispatchers.Main) {
                command("mask to selection","Mask to selection").run()
                command("disable mask","Disable layer mask").run()
                command("stroke path","Stroke path").run() // Existing handler reports an empty path.
                command("brush from selection","Create brush from selection")
                command("pattern from selection","Create pattern from selection")
                command("align layer left","Align layer left")
                command("insert image files","Insert image from files…").run()
                assertEquals(listOf("pickImageLayerFile"),fileCalls)
                command("edit gradient","Edit gradient…").run()
                assertEquals("gradient-edit",vm.dialog)
            }
            val changed=vm.engine.snapshot()
            assertNotNull(changed.selection);assertFalse(changed.active!!.maskEnabled)
            withContext(Dispatchers.Main) {
                command("load channel Outline","Load channel “Outline”").run()
                command("remove effect Gaussian Blur","Remove effect “Gaussian Blur”").run()
            }
            assertTrue(vm.engine.snapshot().active!!.effects.isEmpty())
            assertEquals(android.graphics.Rect(1,1,2,2),vm.engine.snapshot().selection!!.bounds)
            withContext(Dispatchers.Main) {
                val pattern=PatternRes("Tiny checks",Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888))
                val palette=PaletteRes("Sunset",listOf(Color.RED))
                val resources=resourceCommands(vm,emptyList(),emptyList(),listOf(pattern),listOf(palette),emptyList())
                CommandSearch(resources).search("pattern Tiny checks").first().run()
                assertSame(pattern,vm.pattern);assertTrue(vm.fillWithPattern);assertEquals(Tool.BUCKET,vm.tool)
                CommandSearch(resources).search("palette Sunset").first().run()
                assertSame(palette,vm.palette);assertEquals(Sheet.COLOR,vm.sheet)
            }
        } finally {withContext(Dispatchers.Main){store.clear()};dir.deleteRecursively()}
    }
}
