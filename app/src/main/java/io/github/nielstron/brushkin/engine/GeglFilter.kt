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
        initialize(operations.absolutePath, babl.absolutePath)
        ready = true
    }
    private external fun initialize(operations: String, babl: String)
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
    override fun apply(src: IntArray, w: Int, h: Int): IntArray {
        // A neutral displacement must not incur a resampling/color-conversion round trip.
        val neutral = when (operation) {
            "gegl:ripple" -> parameters["amplitude"] == 0.0
            "gegl:whirl-pinch" -> parameters["whirl"] == 0.0 && parameters["pinch"] == 0.0
            "gegl:lens-distortion" -> listOf("main", "edge", "zoom").all { parameters[it] == 0.0 }
            else -> false
        }
        if (neutral) return src.copyOf()
        return NativeGegl.render(src, w, h, operation, parameters.keys.toTypedArray(), parameters.values.toDoubleArray())
    }
    override fun scaled(k: Float) = GeglFilter(name, operation,
        parameters.mapValues { (key, value) -> if (key in distances) (value * k).let { if (key == "size-x" || key == "size-y") it.coerceAtLeast(1.0) else it } else value }, distances)
}
