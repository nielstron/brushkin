package io.github.nielstron.brushkin.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.system.Os
import io.github.nielstron.brushkin.BuildConfig
import java.io.File

/** GEGL modules remain in Android's installed native-library directory. Private symlinks give
 * GEGL and babl separate search directories without copying executable code into app storage. */
object NativeGegl {
    init { System.loadLibrary("brushkin_gegl") }
    private var ready = false
    /** Benchmarks only: false forces the CPU path for whole-image filter renders. */
    @Volatile @JvmField var allowOpenCl = true
    @Synchronized fun prepare(context: Context) {
        if (ready) return
        val native = File(context.applicationInfo.nativeLibraryDir)
        val base = File(context.noBackupFilesDir, "gegl-modules")
        val operations = File(base, "operations").apply { mkdirs() }
        val babl = File(base, "babl").apply { mkdirs() }
        for (dir in listOf(operations, babl)) dir.listFiles()!!.forEach { it.delete() }
        for (library in native.listFiles()!!) {
            val dir = when {
                library.name.startsWith("libgegl_op_") -> operations
                library.name.startsWith("libbabl_ext_") -> babl
                else -> continue
            }
            Os.symlink(library.absolutePath, File(dir, library.name).absolutePath)
        }
        val swap = File(context.cacheDir, "gegl-swap").apply { mkdirs() }
        initialize(operations.absolutePath, babl.absolutePath, swap.absolutePath)
        ready = true
        Thread({ try { startOpenCl(context) } finally { openClSettled.countDown() } }, "gegl-opencl")
            .apply { isDaemon = true }.start()
    }
    private external fun initialize(operations: String, babl: String, swap: String)

    /** Runs the vendor's OpenCL driver, which can take seconds and whose crashes cannot be caught.
     * A marker that outlives [enableOpenCl] after a crash exit means the driver killed the app, so
     * this app + OS build stays on the CPU; an update to either tries the GPU again. */
    private fun startOpenCl(context: Context) {
        val attempt = File(context.noBackupFilesDir, "gegl-opencl-attempt")
        val blocked = File(context.noBackupFilesDir, "gegl-opencl-blocked")
        val build = "${BuildConfig.VERSION_CODE} ${Build.FINGERPRINT}"
        runCatching {
            if (attempt.exists()) {
                if (attempt.readText() == build && lastExitWasCrash(context)) blocked.writeText(build)
                attempt.delete()
            }
        }
        if (runCatching { blocked.readText() == build }.getOrDefault(false)) {
            android.util.Log.w("Brushkin", "GEGL OpenCL disabled: its setup crashed an earlier launch")
            return
        }
        runCatching { attempt.writeText(build) }
        enableOpenCl()
        attempt.delete()
        android.util.Log.i("Brushkin", "GEGL OpenCL GPU available: ${openClActive()}")
    }
    /** Being swiped away or killed for memory during setup says nothing about the driver. */
    private fun lastExitWasCrash(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        val last = context.getSystemService(ActivityManager::class.java)
            .getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return true
        return last.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
            last.reason == ApplicationExitInfo.REASON_CRASH || last.reason == ApplicationExitInfo.REASON_SIGNALED
    }
    private val openClSettled = java.util.concurrent.CountDownLatch(1)
    private external fun enableOpenCl()

    /** Under memory pressure, and whenever the UI is hidden, GEGL keeps only a 32 MB tile cache
     * (the rest moves to its swap file); [restoreTileCache] brings back the full size.
     * Shrinking writes dirty tiles to swap first, so both run on one background thread
     * (in call order) instead of the main thread that delivers the callbacks. */
    private val cacheThread = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "gegl-tile-cache").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    fun trimMemory(level: Int) {
        @Suppress("DEPRECATION") // RUNNING_LOW is no longer sent from API 34 on; UI_HIDDEN and up still are.
        if (ready && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cacheThread.execute { shrinkTileCache(true) }
    }
    fun restoreTileCache() { if (ready) cacheThread.execute { shrinkTileCache(false) } }
    private external fun shrinkTileCache(shrink: Boolean)
    external fun createSession(width: Int, height: Int, operation: String, keys: Array<String>, values: DoubleArray, regional: Boolean): Long
    /** Uploads width×height ARGB ints (row stride width; [pixels] may be larger). */
    external fun updateSession(handle: Long, x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    /** Renders the changes since the last call and returns their rectangle {x, y, width, height}. */
    external fun renderSession(handle: Long): IntArray
    /** Copies part of the last [renderSession] rectangle into [pixels] (row stride width). */
    external fun readSession(handle: Long, x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    external fun destroySession(handle: Long)
    /** A native R'G'B'A u8 GEGL buffer; see [LayerBuffer]. */
    external fun createBuffer(width: Int, height: Int): Long
    /** Uploads width×height ARGB ints (row stride width; [pixels] may be larger). */
    external fun uploadBuffer(handle: Long, x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    external fun destroyBuffer(handle: Long)
    /** Renders [operation] over the whole buffer; the result stays native (see [readRendered]). */
    external fun renderBuffer(handle: Long, operation: String, keys: Array<String>, values: DoubleArray, useOpenCl: Boolean, profile: Boolean): Long
    /** Copies part of a [renderBuffer] result into [pixels] as ARGB ints (row stride width). */
    external fun readRendered(handle: Long, x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    external fun destroyRendered(handle: Long)
    private external fun openClActive(): Boolean
    /** Waits for the background OpenCL setup started by [prepare]. */
    fun isOpenClActive(): Boolean { openClSettled.await(); return openClActive() }
    /** Diagnostic for serial hardware tests: at least one prototype kernel completed its GPU path. */
    external fun lastRenderUsedOpenCl(): Boolean
    external fun describe(operation: String): String
    external fun render(pixels: IntArray, width: Int, height: Int, operation: String,
        keys: Array<String>, values: DoubleArray, useOpenCl: Boolean = true, benchmarkOpenCl: Boolean = false): IntArray
    /** Brushkin 0.6.3's [render] with its whole-image copies; only the equivalence test uses it. */
    external fun renderLegacy(pixels: IntArray, width: Int, height: Int, operation: String,
        keys: Array<String>, values: DoubleArray, useOpenCl: Boolean = true, benchmarkOpenCl: Boolean = false): IntArray
}

/** Render the full extent so global statistics and coordinate-dependent filters have no band seams. */
class GeglFilter(
    override val name: String,
    val operation: String,
    val parameters: Map<String, Double> = emptyMap(),
    private val distances: Set<String> = emptySet(),
) : Filter {
    override val wholeImage = true
    // A neutral displacement must not incur a resampling/color-conversion round trip.
    private val neutral get() = when (operation) {
        "gegl:ripple" -> parameters["amplitude"] == 0.0
        "gegl:whirl-pinch" -> parameters["whirl"] == 0.0 && parameters["pinch"] == 0.0
        "gegl:lens-distortion" -> listOf("main", "edge", "zoom").all { parameters[it] == 0.0 }
        else -> false
    }
    override val gegl: GeglFilter? get() = if (neutral) null else this
    override fun incremental(width: Int, height: Int): IncrementalFilter? =
        if (neutral) null else GeglIncremental(width, height, operation, parameters)
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        if (neutral) return src.copyOf()
        return NativeGegl.render(src, w, h, operation, parameters.keys.toTypedArray(), parameters.values.toDoubleArray(), NativeGegl.allowOpenCl)
    }
    override fun scaled(k: Float) = GeglFilter(name, operation,
        parameters.mapValues { (key, value) -> if (key in distances) (value * k).let { if (key == "size-x" || key == "size-y") it.coerceAtLeast(1.0) else it } else value }, distances)
}
