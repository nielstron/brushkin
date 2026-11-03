package io.github.nielstron.brushkin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.CompiledModel.GpuOptions
import com.google.ai.edge.litert.Environment
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Developer probe: runs the GPU encoder (cache/models/mobilesam-2/mobilesam_encoder_gpu.tflite)
 * with several GPU configurations on a test image and compares embeddings and masks with the
 * CPU. Results in logcat (tag Brushkin). Does nothing when the model isn't there.
 */
@RunWith(AndroidJUnit4::class)
class GpuEncoderProbe {
    /** Every model in files/probe/: relative GPU-vs-CPU error on the test image. */
    @Test fun prefixes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val files = File(ctx.filesDir, "probe").listFiles()?.filter { it.name.endsWith(".tflite") }?.sortedBy { it.name } ?: return
        val env = Environment.create(ctx)
        val image = testInput()
        for (f in files) {
            // "<name>_<n>.tflite": a flat input of n values (seeded random); otherwise the test image.
            val n = f.name.substringBeforeLast('.').substringAfterLast('_').toIntOrNull()
            val input = if (n != null) java.util.Random(7).let { r -> FloatArray(n) { r.nextGaussian().toFloat() } } else image
            fun run(o: CompiledModel.Options): FloatArray {
                val m = CompiledModel.create(f.absolutePath, o, env); val i = m.createInputBuffers(); val out = m.createOutputBuffers()
                i[0].writeFloat(input); m.run(i, out); val r = out[0].readFloat(); (i + out).forEach { it.close() }; m.close(); return r
            }
            val c = run(CompiledModel.Options(Accelerator.CPU))
            val g = try { run(CompiledModel.Options(Accelerator.GPU).apply { gpuOptions = GpuOptions(precision = GpuOptions.Precision.FP32) }) } catch (e: Exception) {
                android.util.Log.i("Brushkin", "Probe ${f.name}: GPU failed: ${e.message}"); continue
            }
            val err = c.indices.sumOf { kotlin.math.abs(g[it] - c[it]).toDouble() } / c.size
            val mag = c.sumOf { kotlin.math.abs(it).toDouble() } / c.size
            android.util.Log.i("Brushkin", "Probe ${f.name}: ${c.size} values, relative GPU error %.4f".format(err / mag))
        }
    }

    private fun testInput(): FloatArray {
        val img = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        Canvas(img).apply { drawColor(Color.rgb(230, 230, 230)); drawRect(200f, 200f, 500f, 500f, Paint().apply { color = Color.RED }); drawCircle(750f, 380f, 120f, Paint().apply { color = Color.BLUE }) }
        val px = IntArray(1024 * 768).also { img.getPixels(it, 0, 1024, 0, 0, 1024, 768) }
        val mean = floatArrayOf(123.675f, 116.28f, 103.53f); val std = floatArrayOf(58.395f, 57.12f, 57.375f)
        val input = FloatArray(3 * 1024 * 1024)
        for (y in 0 until 768) for (x in 0 until 1024) { val c = px[y * 1024 + x]; val i = y * 1024 + x
            input[i] = (((c shr 16) and 0xFF) - mean[0]) / std[0]; input[1024 * 1024 + i] = (((c shr 8) and 0xFF) - mean[1]) / std[1]; input[2 * 1024 * 1024 + i] = ((c and 0xFF) - mean[2]) / std[2] }
        return input
    }

    @Test fun probe() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.cacheDir, "models/mobilesam-2")
        val encFile = File(dir, "mobilesam_encoder_gpu.tflite").takeIf { it.exists() } ?: return
        val env = Environment.create(ctx)
        // Red square (tap inside it) and a blue circle that must not be selected.
        val img = Bitmap.createBitmap(1024, 768, Bitmap.Config.ARGB_8888)
        Canvas(img).apply { drawColor(Color.rgb(230, 230, 230)); drawRect(200f, 200f, 500f, 500f, Paint().apply { color = Color.RED }); drawCircle(750f, 380f, 120f, Paint().apply { color = Color.BLUE }) }
        val px = IntArray(1024 * 768).also { img.getPixels(it, 0, 1024, 0, 0, 1024, 768) }
        val mean = floatArrayOf(123.675f, 116.28f, 103.53f); val std = floatArrayOf(58.395f, 57.12f, 57.375f)
        val input = FloatArray(3 * 1024 * 1024)
        for (y in 0 until 768) for (x in 0 until 1024) { val c = px[y * 1024 + x]; val i = y * 1024 + x
            input[i] = (((c shr 16) and 0xFF) - mean[0]) / std[0]; input[1024 * 1024 + i] = (((c shr 8) and 0xFF) - mean[1]) / std[1]; input[2 * 1024 * 1024 + i] = ((c and 0xFF) - mean[2]) / std[2] }
        val dec = CompiledModel.create(File(dir, "mobilesam_decoder.tflite").absolutePath, CompiledModel.Options(Accelerator.CPU), env)
        fun masks(emb: FloatArray): String {
            val i = dec.createInputBuffers(); val o = dec.createOutputBuffers()
            i[0].writeFloat(emb); i[1].writeFloat(floatArrayOf(350f, 350f, 0f, 0f)); i[2].writeFloat(floatArrayOf(1f, -1f))
            dec.run(i, o); val m = o[0].readFloat(); val sc = o[1].readFloat(); val b = sc.indices.maxBy { sc[it] }
            (i + o).forEach { it.close() }
            fun at(x: Int, y: Int) = m[b * 65536 + (y / 4) * 256 + x / 4] > 0f
            return "square=${at(350, 350)} circle=${at(750, 380)}"
        }
        fun run(opts: CompiledModel.Options): FloatArray {
            val m = CompiledModel.create(encFile.absolutePath, opts, env)
            val ins = m.createInputBuffers(); val outs = m.createOutputBuffers()
            ins[0].writeFloat(input); m.run(ins, outs); val r = outs[0].readFloat()
            (ins + outs).forEach { it.close() }; m.close(); return r
        }
        val cpu = run(CompiledModel.Options(Accelerator.CPU))
        android.util.Log.i("Brushkin", "Probe CPU: ${masks(cpu)}")
        val variants = linkedMapOf(
            "FP32" to GpuOptions(precision = GpuOptions.Precision.FP32),
            "FP32 buffer" to GpuOptions(precision = GpuOptions.Precision.FP32, bufferStorageType = GpuOptions.BufferStorageType.BUFFER),
            "FP32 no-texture-weights" to GpuOptions(precision = GpuOptions.Precision.FP32, preferTextureWeights = false),
            "FP32 inf-capping" to GpuOptions(precision = GpuOptions.Precision.FP32, infiniteFloatCapping = true),
            "FP32 OpenGL" to GpuOptions(precision = GpuOptions.Precision.FP32, backend = GpuOptions.Backend.OPENGL),
            "FP16 inf-capping" to GpuOptions(precision = GpuOptions.Precision.FP16, infiniteFloatCapping = true),
        )
        for ((name, g) in variants) {
            val r = try { run(CompiledModel.Options(Accelerator.GPU).apply { gpuOptions = g }) } catch (e: Exception) {
                android.util.Log.i("Brushkin", "Probe $name: failed: ${e.message}"); continue
            }
            val err = r.indices.sumOf { kotlin.math.abs(r[it] - cpu[it]).toDouble() } / r.size
            val maxErr = r.indices.maxOf { kotlin.math.abs(r[it] - cpu[it]) }
            android.util.Log.i("Brushkin", "Probe $name: mean err %.5f, max err %.3f, ${masks(r)}".format(err, maxErr))
        }
        dec.close()
    }
}
