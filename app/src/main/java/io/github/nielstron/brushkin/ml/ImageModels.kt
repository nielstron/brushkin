package io.github.nielstron.brushkin.ml

import android.graphics.Bitmap
import com.google.ai.edge.litert.CompiledModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/**
 * Image-to-image models (tools/export_models.py). All are fully convolutional with fixed input
 * sizes, so larger images run in overlapping tiles; each runs on the GPU when it compiles there.
 */
class ImageModels(private val store: ModelStore) {
    companion object {
        private const val R = "models-2"
        /** NAFNet-SIDD-width32 (MIT): real camera noise. */
        val DENOISE = ModelFile(R, "nafnet_denoise_256.tflite", 117_171_864L, "d3899ae5d20bf60b0d2fda1916897f22215c218ebff7b4ba6e74dd5584ca87ae")
        /** NAFNet-GoPro-width32 (MIT): camera shake and motion blur. */
        val DEBLUR = ModelFile(R, "nafnet_deblur_384.tflite", 68_980_796L, "4c57d3b981f93530ae5d68c218f6894863b3df8c8a8c76dee5abc85ae3a769f6")
        /** PLKSR-Rep (MIT, NTIRE 2026 mobile real-world super-resolution winner): ×4. */
        val UPSCALE = ModelFile(R, "plksr_x4_128.tflite", 12_824_448L, "21e4abe91ceaa4224a4bd5603a2220e51c9fe89a791c892db4674a03774e3c47")
        /** ZipDepth (MIT): relative depth, larger = nearer. */
        val DEPTH = ModelFile(R, "zipdepth_512.tflite", 24_653_328L, "ab46eb82935ecae5549ad9413a73e20db73e75661484cb96dada6c5f44b16e46")
        /** MI-GAN Places2 512 (MIT): fills a hole from its surroundings. */
        val INPAINT = ModelFile(R, "migan_512.tflite", 28_068_036L, "10fda63d6f145f022cd827ac5b17812714956fbb821953daeb795b02ed31024d")
        const val DEPTH_SIDE = 512
        private const val INPAINT_SIDE = 512
    }

    private val lock = Mutex()
    private val compiled = HashMap<ModelFile, CompiledModel>()

    private fun model(m: ModelFile): CompiledModel = compiled.getOrPut(m) {
        // ZipDepth doesn't compile for LiteRT's GPU backend (and is quick on the CPU anyway).
        val (c, accel) = store.compile(m, gpu = m !== DEPTH)
        android.util.Log.i("Brushkin", "${m.name} on the $accel")
        c
    }

    private suspend fun <T> use(m: ModelFile, block: suspend (CompiledModel) -> T): T = lock.withLock {
        if (!store.ready(listOf(m))) { close(); throw ModelUnavailable("The model isn't downloaded") }
        withContext(Dispatchers.Default) { block(model(m)) }
    }

    suspend fun denoise(src: Bitmap, progress: (Float) -> Unit) = use(DENOISE) { tiled(it, src, 256, 1, 32, progress) }
    // On some sharp (unblurred) photos NAFNet-GoPro's output leaves the 0…1 range in places; those tiles stay as they were.
    suspend fun deblur(src: Bitmap, progress: (Float) -> Unit) = use(DEBLUR) { tiled(it, src, 384, 1, 32, progress, checkRange = true) }

    /** [src] enlarged [factor] (2 or 4) times: ×4 by the model, ×2 by halving that. */
    suspend fun upscale(src: Bitmap, factor: Int, progress: (Float) -> Unit): Bitmap = use(UPSCALE) {
        val x4 = tiled(it, src, 128, 4, 16, progress)
        if (factor == 4) x4 else Bitmap.createScaledBitmap(x4, src.width * factor, src.height * factor, true).also { x4.recycle() }
    }

    /** Relative depth of [src] as a [DEPTH_SIDE]² map (the image stretched to a square), 0 = farthest, 1 = nearest. */
    suspend fun depth(src: Bitmap): FloatArray = use(DEPTH) { m ->
        val s = Bitmap.createScaledBitmap(src, DEPTH_SIDE, DEPTH_SIDE, true)
        val px = IntArray(DEPTH_SIDE * DEPTH_SIDE).also { s.getPixels(it, 0, DEPTH_SIDE, 0, 0, DEPTH_SIDE, DEPTH_SIDE) }
        val n = DEPTH_SIDE * DEPTH_SIDE
        val input = FloatArray(3 * n)
        for (i in 0 until n) { val c = px[i]; input[i] = ((c shr 16) and 0xFF) / 255f; input[n + i] = ((c shr 8) and 0xFF) / 255f; input[2 * n + i] = (c and 0xFF) / 255f }
        val d = m.runOnce(input)[0]
        val lo = d.min(); val hi = d.max(); val span = (hi - lo).coerceAtLeast(1e-6f)
        FloatArray(n) { (d[it] - lo) / span }
    }

    /**
     * [src] with the area of [hole] (0…255 per pixel, the selection) filled in from its
     * surroundings. The model sees a square around the hole scaled to 512², so very large holes come
     * back softer than the rest of the image.
     */
    suspend fun inpaint(src: Bitmap, hole: ByteArray): Bitmap = use(INPAINT) { m ->
        val w = src.width; val h = src.height
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if ((hole[y * w + x].toInt() and 0xFF) > 0) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        if (r < 0) return@use src
        // A margin around the hole (object edges, shadows), then a square crop with context.
        val grow = maxOf(3, maxOf(w, h) / 170)
        val side = minOf(maxOf(w, h), (maxOf(r - l, b - t) * 1.6f + 64 + 2 * grow).roundToInt())
        val sw = minOf(side, w); val sh = minOf(side, h)
        val left = ((l + r) / 2 - sw / 2).coerceIn(0, w - sw); val top = ((t + b) / 2 - sh / 2).coerceIn(0, h - sh)
        val crop = IntArray(sw * sh).also { src.getPixels(it, 0, sw, left, top, sw, sh) }
        val cm = ByteArray(sw * sh) { i -> if ((hole[(top + i / sw) * w + left + i % sw].toInt() and 0xFF) >= 128) 1 else 0 }
        val grown = dilate(cm, sw, sh, grow)
        // Model input: [known - 0.5, RGB in -1…1 times known] at 512².
        val S = INPAINT_SIDE; val n = S * S
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(crop, sw, sh, Bitmap.Config.ARGB_8888), S, S, true)
        val sp = IntArray(n).also { small.getPixels(it, 0, S, 0, 0, S, S) }
        val input = FloatArray(4 * n)
        for (i in 0 until n) {
            val known = if (grown[(i / S) * sh / S * sw + (i % S) * sw / S].toInt() == 0) 1f else 0f
            val c = sp[i]
            input[i] = known - 0.5f
            input[n + i] = (((c shr 16) and 0xFF) / 127.5f - 1f) * known
            input[2 * n + i] = (((c shr 8) and 0xFF) / 127.5f - 1f) * known
            input[3 * n + i] = ((c and 0xFF) / 127.5f - 1f) * known
        }
        val o = m.runOnce(input)[0]
        val res = IntArray(n) { i ->
            fun ch(v: Float) = ((v * 0.5f + 0.5f) * 255f).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (ch(o[i]) shl 16) or (ch(o[n + i]) shl 8) or ch(o[2 * n + i])
        }
        val back = Bitmap.createScaledBitmap(Bitmap.createBitmap(res, S, S, Bitmap.Config.ARGB_8888), sw, sh, true)
        val bp = IntArray(sw * sh).also { back.getPixels(it, 0, sw, 0, 0, sw, sh) }
        // Blend with a slightly soft edge; keep the original alpha.
        val soft = io.github.nielstron.brushkin.engine.Selection.blurBytes(ByteArray(sw * sh) { if (grown[it].toInt() != 0) -1 else 0 }, sw, sh, 2)
        for (i in 0 until sw * sh) {
            val a = soft[i].toInt() and 0xFF
            if (a == 0) continue
            val c0 = crop[i]; val c1 = bp[i]
            fun mix(sh2: Int) = ((((c0 shr sh2) and 0xFF) * (255 - a) + ((c1 shr sh2) and 0xFF) * a) / 255)
            crop[i] = (c0 and 0xFF000000.toInt()) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        out.setPixels(crop, 0, sw, left, top, sw, sh)
        out
    }

    /** Square dilation of a 0/1 mask by [r] (separable running max). */
    private fun dilate(m: ByteArray, w: Int, h: Int, r: Int): ByteArray {
        val tmp = ByteArray(w * h); val out = ByteArray(w * h)
        for (y in 0 until h) {
            var last = -1_000_000
            for (x in 0 until w) if (m[y * w + x].toInt() != 0) last = x else if (x - last <= r) tmp[y * w + x] = 1
            last = 1_000_000
            for (x in w - 1 downTo 0) if (m[y * w + x].toInt() != 0) { last = x; tmp[y * w + x] = 1 } else if (last - x <= r) tmp[y * w + x] = 1
        }
        for (x in 0 until w) {
            var last = -1_000_000
            for (y in 0 until h) if (tmp[y * w + x].toInt() != 0) { last = y; out[y * w + x] = 1 } else if (y - last <= r) out[y * w + x] = 1
            last = 1_000_000
            for (y in h - 1 downTo 0) if (tmp[y * w + x].toInt() != 0) last = y else if (last - y <= r) out[y * w + x] = 1
        }
        return out
    }

    /**
     * Runs [m] (input [1,3,tile,tile] RGB 0…1, output [1,3,tile·scale,tile·scale]) over [src] in
     * tiles overlapping by [overlap] px, mirrored at the borders; keeps each tile's center.
     * Alpha is carried over from the source (interpolated when scaling).
     */
    private suspend fun tiled(m: CompiledModel, src: Bitmap, tile: Int, scale: Int, overlap: Int, progress: (Float) -> Unit, checkRange: Boolean = false): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h).also { src.getPixels(it, 0, w, 0, 0, w, h) }
        val out = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888)
        val step = tile - 2 * overlap
        val cols = (w + step - 1) / step; val rows = (h + step - 1) / step
        val n = tile * tile; val on = n * scale * scale; val ot = tile * scale
        val input = FloatArray(3 * n)
        val opaque = px.all { (it ushr 24) == 0xFF }
        fun mirror(i: Int, size: Int): Int {
            if (size == 1) return 0
            val period = 2 * (size - 1)
            val k = Math.floorMod(i, period)
            return if (k < size) k else period - k
        }
        for (ty in 0 until rows) for (tx in 0 until cols) {
            coroutineContext.ensureActive()
            val x0 = tx * step; val y0 = ty * step
            for (j in 0 until tile) {
                val sy = mirror(y0 - overlap + j, h) * w
                for (i in 0 until tile) {
                    val c = px[sy + mirror(x0 - overlap + i, w)]; val k = j * tile + i
                    input[k] = ((c shr 16) and 0xFF) / 255f; input[n + k] = ((c shr 8) and 0xFF) / 255f; input[2 * n + k] = (c and 0xFF) / 255f
                }
            }
            val o = m.runOnce(input)[0]
            val cw = minOf(step, w - x0) * scale; val ch = minOf(step, h - y0) * scale
            if (checkRange && o.count { it < -0.5f || it > 1.5f } > o.size / 20000) {
                android.util.Log.w("Brushkin", "tile $tx×$ty: model output out of range, kept as it was")
                System.arraycopy(input, 0, o, 0, 3 * n)
            }
            val row = IntArray(cw)
            for (yy in 0 until ch) {
                val oy = overlap * scale + yy
                for (xx in 0 until cw) {
                    val k = oy * ot + overlap * scale + xx
                    fun c(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val a = if (opaque) 0xFF else alphaAt(px, w, h, (x0 * scale + xx + 0.5f) / scale - 0.5f, (y0 * scale + yy + 0.5f) / scale - 0.5f)
                    row[xx] = (a shl 24) or (c(o[k]) shl 16) or (c(o[on + k]) shl 8) or c(o[2 * on + k])
                }
                out.setPixels(row, 0, cw, x0 * scale, y0 * scale + yy, cw, 1)
            }
            progress((ty * cols + tx + 1) / (rows * cols).toFloat())
        }
        return out
    }

    private fun alphaAt(px: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x = fx.coerceIn(0f, w - 1f); val y = fy.coerceIn(0f, h - 1f)
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = minOf(w - 1, x0 + 1); val y1 = minOf(h - 1, y0 + 1)
        val tx = x - x0; val ty = y - y0
        fun a(xx: Int, yy: Int) = (px[yy * w + xx] ushr 24).toFloat()
        return ((a(x0, y0) * (1 - tx) + a(x1, y0) * tx) * (1 - ty) + (a(x0, y1) * (1 - tx) + a(x1, y1) * tx) * ty).roundToInt()
    }

    fun close() { compiled.values.forEach { it.close() }; compiled.clear() }
}
