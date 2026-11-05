package io.github.nielstron.brushkin.engine

import android.content.Context
import android.system.Os
import java.io.File

/** GEGL modules remain in Android's installed native-library directory. Private symlinks give
 * GEGL and babl separate search directories without copying executable code into app storage. */
object NativeGegl {
    init { System.loadLibrary("brushkin_gegl") }
    private var ready = false
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
    }
    private external fun initialize(operations: String, babl: String, swap: String)
    external fun createSession(width: Int, height: Int, operation: String, keys: Array<String>, values: DoubleArray, regional: Boolean): Long
    external fun updateSession(handle: Long, x: Int, y: Int, width: Int, height: Int, pixels: IntArray)
    external fun renderSession(handle: Long): IntArray
    external fun destroySession(handle: Long)
    external fun describe(operation: String): String
    external fun render(pixels: IntArray, width: Int, height: Int, operation: String,
        keys: Array<String>, values: DoubleArray): IntArray
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
    override fun incremental(width: Int, height: Int): IncrementalFilter? =
        if (neutral) null else GeglIncremental(width, height, operation, parameters)
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        if (neutral) return src.copyOf()
        return NativeGegl.render(src, w, h, operation, parameters.keys.toTypedArray(), parameters.values.toDoubleArray())
    }
    override fun scaled(k: Float) = GeglFilter(name, operation,
        parameters.mapValues { (key, value) -> if (key in distances) (value * k).let { if (key == "size-x" || key == "size-y") it.coerceAtLeast(1.0) else it } else value }, distances)
}
