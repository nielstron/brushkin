package io.github.nielstron.brushkin.ml

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object ObjectSelect {
    class Mask(val bytes: ByteArray, val width: Int, val height: Int)
    /** A tap: on the object ([positive]) or on something that isn't part of it. */
    class Point(val x: Float, val y: Float, val positive: Boolean)
    /** [logits]: SAM's low-resolution mask, fed back in when the selection is refined. */
    class Result(val mask: Mask?, val logits: FloatArray)
}

/**
 * Object selection with MobileSAM (Apache-2.0): an image encoder (run once per image, on the GPU
 * when there is one) and a prompt decoder (per tap, on the CPU) that takes up to [POINTS] taps or a
 * box plus the previous mask, for refining a selection tap by tap.
 */
class ObjectSelectClient(private val store: ModelStore) {
    companion object {
        // Encoders: float weights with GPU-friendly attention for the GPU, 8-bit weights for the CPU
        // (tools/export_mobilesam.py). Decoder with refinement inputs (tools/export_models.py).
        val ENC_CPU = ModelFile("mobilesam_encoder.tflite", 11_301_680L, "00d8a83a751abc7723d071bbee9a66ab039faf2821976e9de2da0eb07af78fca")
        val ENC_GPU = ModelFile("mobilesam_encoder_gpu.tflite", 28_339_460L, "8aae003faf1b1c3fb27af0a6b6961a36d8d4084613c576be6b200ce399f9f3f2")
        val DEC = ModelFile("mobilesam_decoder_refine.tflite", 11_323_152L, "e0b8905036bb93427852a3bb5612e4c73ccb269e3cda138bfc001b2646aec0c0")
        /** Most taps per object (the decoder takes up to 9 prompt points). */
        const val POINTS = 8
        private const val SIDE = 1024
        private const val LOW = 256 // the decoder's masks are 256×256 over the padded 1024×1024 input
        private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    }

    private val lock = Mutex()
    private var encoder: CompiledModel? = null
    private var decoder: CompiledModel? = null
    private var accel = "CPU"

    /** Embedding of the last image, so further taps on it only run the (fast) decoder. */
    private var cacheKey: Long = 0
    private var cached: FloatArray? = null

    val files get() = listOf(if (store.hasGpu) ENC_GPU else ENC_CPU, DEC)

    private fun models(): Pair<CompiledModel, CompiledModel> {
        val enc = encoder ?: if (store.hasGpu) store.compile(ENC_GPU).also { accel = it.second }.first
            else store.compile(ENC_CPU, gpu = false).also { accel = "CPU" }.first
        encoder = enc
        val dec = decoder ?: store.compile(DEC, gpu = false).first.also { decoder = it }
        return enc to dec
    }

    /** Compiles the models in the background (e.g. when the Object tool is picked) so a tap is fast. */
    suspend fun prewarm() = lock.withLock {
        if (store.ready(files)) withContext(Dispatchers.Default) { runCatching { models() } }
    }

    /**
     * The object marked by [points] (image pixels) or [box] in [image], refining [previous]
     * (a [ObjectSelect.Result.logits]) when given.
     */
    suspend fun select(image: Bitmap, points: List<ObjectSelect.Point>, box: RectF? = null, previous: FloatArray? = null): ObjectSelect.Result = lock.withLock {
        // The cache may have been cleared since the last use.
        if (!store.ready(files)) { close(); throw ModelUnavailable("The object selection model isn't downloaded") }
        withContext(Dispatchers.Default) {
            val (enc, dec) = models()
            val w = image.width; val h = image.height
            val k = SIDE / maxOf(w, h).toFloat()
            val t0 = System.nanoTime()
            val emb = embedding(enc, image, k)
            val t1 = System.nanoTime()
            // Prompts as in SAM's ONNX export: the box corners (labels 2, 3), the taps (1 = object,
            // 0 = not), and one padding point (label -1) when there's no box. The decoder has a
            // signature per prompt count ("t2"…"t9").
            val taps = points.takeLast(if (box != null) POINTS - 1 else POINTS)
            val tokens = (if (box != null) 2 else 1) + taps.size
            val coords = FloatArray(tokens * 2); val labels = FloatArray(tokens) { -1f }
            var n = 0
            if (box != null) {
                coords[0] = box.left * k; coords[1] = box.top * k; coords[2] = box.right * k; coords[3] = box.bottom * k
                labels[0] = 2f; labels[1] = 3f; n = 2
            }
            for (p in taps) { coords[n * 2] = p.x * k; coords[n * 2 + 1] = p.y * k; labels[n] = if (p.positive) 1f else 0f; n++ }
            val maskIn = previous ?: FloatArray(LOW * LOW)
            val outs = dec.runOnce(emb, coords, labels, maskIn, floatArrayOf(if (previous != null) 1f else 0f), signature = "t$tokens")
            val all = outs.first { it.size == 4 * LOW * LOW }; val sc = outs.first { it.size == 4 }
            android.util.Log.i("Brushkin", "ObjectSelect: %s encoder %d ms%s, decoder %d ms".format(accel, (t1 - t0) / 1_000_000, if (t1 - t0 < 1_000_000) " (cached)" else "", (System.nanoTime() - t1) / 1_000_000))
            // One tap is ambiguous: SAM proposes several masks, take the one it's most confident in.
            // With more prompts its single-mask output (the first) is the right one.
            val best = if (box == null && taps.size == 1 && previous == null) sc.indices.maxBy { sc[it] } else 0
            val low = all.copyOfRange(best * LOW * LOW, (best + 1) * LOW * LOW)
            ObjectSelect.Result(toMask(low, w, h, k), low)
        }
    }

    /** The low-res mask covers the padded 1024² input; sample it bilinearly per image pixel. */
    private fun toMask(low: FloatArray, w: Int, h: Int, k: Float): ObjectSelect.Mask? {
        val bytes = ByteArray(w * h)
        val f = LOW / SIDE.toFloat() * k
        for (py in 0 until h) {
            val ly = ((py + 0.5f) * f - 0.5f).coerceIn(0f, LOW - 1f)
            val y0 = ly.toInt(); val y1 = minOf(LOW - 1, y0 + 1); val ty = ly - y0
            for (px in 0 until w) {
                val lx = ((px + 0.5f) * f - 0.5f).coerceIn(0f, LOW - 1f)
                val x0 = lx.toInt(); val x1 = minOf(LOW - 1, x0 + 1); val tx = lx - x0
                val v = (low[y0 * LOW + x0] * (1 - tx) + low[y0 * LOW + x1] * tx) * (1 - ty) +
                    (low[y1 * LOW + x0] * (1 - tx) + low[y1 * LOW + x1] * tx) * ty
                if (v > 0f) bytes[py * w + px] = -1
            }
        }
        return if (bytes.none { it.toInt() != 0 }) null else ObjectSelect.Mask(bytes, w, h)
    }

    private fun embedding(enc: CompiledModel, image: Bitmap, k: Float): FloatArray {
        val key = imageKey(image)
        cached?.let { if (key == cacheKey) return it }
        val nw = (image.width * k).toInt().coerceIn(1, SIDE); val nh = (image.height * k).toInt().coerceIn(1, SIDE)
        val scaled = Bitmap.createScaledBitmap(image, nw, nh, true)
        val px = IntArray(nw * nh); scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        // NCHW, normalized, zero padded to 1024×1024 (SAM's preprocessing).
        val input = FloatArray(3 * SIDE * SIDE)
        for (yy in 0 until nh) for (xx in 0 until nw) {
            val c = px[yy * nw + xx]; val i = yy * SIDE + xx
            input[i] = (((c shr 16) and 0xFF) - MEAN[0]) / STD[0]
            input[SIDE * SIDE + i] = (((c shr 8) and 0xFF) - MEAN[1]) / STD[1]
            input[2 * SIDE * SIDE + i] = ((c and 0xFF) - MEAN[2]) / STD[2]
        }
        val emb = enc.runOnce(input)[0]
        cacheKey = key; cached = emb
        return emb
    }

    /** Cheap fingerprint of an image: size plus a sparse sample of its pixels. */
    private fun imageKey(b: Bitmap): Long {
        var key = (b.width.toLong() shl 32) xor b.height.toLong()
        val sx = maxOf(1, b.width / 32); val sy = maxOf(1, b.height / 32)
        var yy = 0
        while (yy < b.height) { var xx = 0; while (xx < b.width) { key = key * 31 + b.getPixel(xx, yy); xx += sx }; yy += sy }
        return key
    }

    fun close() { encoder?.close(); decoder?.close(); encoder = null; decoder = null; cached = null }
}
