package io.github.nielstron.brushkin.ml

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object ObjectSelect {
    class Mask(val bytes: ByteArray, val width: Int, val height: Int)
}

/**
 * Object selection: MobileSAM (Apache-2.0) on LiteRT. The model isn't in the
 * APK: it's downloaded on request from the project's GitHub release, checked against pinned
 * SHA-256 hashes (tools/export_mobilesam.py reproduces the files), and kept in the cache
 * directory, so clearing the app's cache frees the space.
 */
class ObjectSelectClient(private val context: Context) {
    companion object {
        private const val BASE = "https://github.com/nielstron/brushkin/releases/download/models-mobilesam-2/"
        // CPU encoder (8-bit weights), GPU encoder (float weights, GPU-friendly attention), decoder.
        private val ENC_CPU = Triple("mobilesam_encoder.tflite", 11_301_680L, "00d8a83a751abc7723d071bbee9a66ab039faf2821976e9de2da0eb07af78fca")
        private val ENC_GPU = Triple("mobilesam_encoder_gpu.tflite", 28_339_460L, "8aae003faf1b1c3fb27af0a6b6961a36d8d4084613c576be6b200ce399f9f3f2")
        private val DEC = Triple("mobilesam_decoder.tflite", 8_642_560L, "2b5ed9f6b63000d394939208001833591dc93e322cf2d83a2ba9b59beb2b6e01")
        private const val SIDE = 1024
        private const val LOW = 256 // the decoder's masks are 256×256 over the padded 1024×1024 input
        private val MEAN = floatArrayOf(123.675f, 116.28f, 103.53f)
        private val STD = floatArrayOf(58.395f, 57.12f, 57.375f)
    }

    private val dir = File(context.cacheDir, "models/mobilesam-2")
    private val lock = Mutex()
    private var env: Environment? = null
    private var encoder: CompiledModel? = null
    private var decoder: CompiledModel? = null
    /** Which accelerator the encoder ended up on (GPU when available, else CPU). */
    private var accel = "CPU"

    /** Embedding of the last image, so further taps on it only run the (fast) decoder. */
    private var cacheKey: Long = 0
    private var cached: FloatArray? = null

    /** Whether this device has a GPU LiteRT can use (then the GPU encoder is downloaded). */
    private val gpu: Boolean by lazy {
        runCatching<Boolean> { env().getAvailableAccelerators().contains(Accelerator.GPU) }.getOrDefault(false)
    }
    private val files get() = listOf(if (gpu) ENC_GPU else ENC_CPU, DEC)

    /** Size of the download for this device, shown before asking. */
    fun downloadMb(): Long = files.sumOf { it.second } / 1_000_000

    fun needsDownload(): Boolean = files.any { (n, size, _) -> File(dir, n).length() != size }

    /** Downloads the model; [progress] gets 0…1. Every file is verified before it's kept. */
    suspend fun download(progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val total = files.sumOf { it.second }.toFloat()
        var done = 0L
        for ((name, size, sha) in files) {
            val target = File(dir, name)
            if (target.length() == size) { done += size; continue }
            val part = File(dir, "$name.part")
            val md = MessageDigest.getInstance("SHA-256")
            var url = URL(BASE + name)
            var conn: HttpURLConnection
            // Follow GitHub's redirect to its download host (HttpURLConnection won't switch hosts).
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000 }
                if (conn.responseCode in 300..399) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                break
            }
            if (conn.responseCode != 200) throw ObjectSelectUnavailable("Download failed (HTTP ${conn.responseCode})")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        progress((done / total).coerceAtMost(1f))
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != sha) { part.delete(); throw ObjectSelectUnavailable("The downloaded model is corrupt; try again") }
            part.renameTo(target)
        }
        progress(1f)
    }

    private fun env(): Environment = env ?: Environment.create(context).also { env = it }

    /** Compiles the encoder for the GPU (float32: float16 overflows in this model), else the CPU. */
    private fun compileEncoder(e: Environment): CompiledModel {
        val file = File(dir, (if (gpu) ENC_GPU else ENC_CPU).first)
        if (gpu) runCatching {
            val cache = File(context.cacheDir, "models/gpu-cache").apply { mkdirs() }
            val opts = CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32,
                    serializationDir = cache.absolutePath, modelCacheKey = ENC_GPU.third.take(16), serializeProgramCache = true)
            }
            return CompiledModel.create(file.absolutePath, opts, e).also { accel = "GPU" }
        }.onFailure { android.util.Log.w("Brushkin", "ObjectSelect: GPU unavailable (${it.message}), using CPU") }
        accel = "CPU"
        return CompiledModel.create(file.absolutePath, cpuOptions(), e)
    }

    private fun cpuOptions() = CompiledModel.Options(Accelerator.CPU).apply {
        cpuOptions = CompiledModel.CpuOptions(numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }

    private fun models(): Pair<CompiledModel, CompiledModel> {
        val e = env()
        val enc = encoder ?: compileEncoder(e).also { encoder = it }
        // The decoder is small and runs per tap: the CPU avoids GPU upload overhead.
        val dec = decoder ?: CompiledModel.create(File(dir, DEC.first).absolutePath, cpuOptions(), e).also { decoder = it }
        return enc to dec
    }

    /** Compiles the models in the background (e.g. when the Object tool is picked) so a tap is fast. */
    suspend fun prewarm() = lock.withLock {
        if (!needsDownload()) withContext(Dispatchers.Default) { runCatching { models() } }
    }

    /** The object at ([x], [y]) in [image] pixels, or the main subject when [x] < 0. */
    suspend fun select(image: Bitmap, x: Int, y: Int): ObjectSelect.Mask? = lock.withLock {
        // The cache may have been cleared since the last use.
        if (needsDownload()) { close(); throw ObjectSelectUnavailable("The object selection model isn't downloaded") }
        withContext(Dispatchers.Default) {
            val (enc, dec) = models()
            val w = image.width; val h = image.height
            val k = SIDE / maxOf(w, h).toFloat()
            val t0 = System.nanoTime()
            val emb = embedding(enc, image, k)
            val t1 = System.nanoTime()
            // Prompt: the tap plus SAM's padding point, or a box around almost everything for
            // "whole foreground".
            val coords = if (x >= 0) floatArrayOf(x * k, y * k, 0f, 0f) else floatArrayOf(w * 0.03f * k, h * 0.03f * k, w * 0.97f * k, h * 0.97f * k)
            val labels = if (x >= 0) floatArrayOf(1f, -1f) else floatArrayOf(2f, 3f)
            val ins = dec.createInputBuffers(); val outs = dec.createOutputBuffers()
            ins[0].writeFloat(emb); ins[1].writeFloat(coords); ins[2].writeFloat(labels)
            dec.run(ins, outs)
            val all = outs[0].readFloat(); val sc = outs[1].readFloat()
            (ins + outs).forEach { it.close() }
            android.util.Log.i("Brushkin", "ObjectSelect: LiteRT 2 %s, encoder %d ms%s, decoder %d ms".format(accel, (t1 - t0) / 1_000_000, if (t1 - t0 < 1_000_000) " (cached)" else "", (System.nanoTime() - t1) / 1_000_000))
            // SAM proposes several masks; take the one it's most confident in.
            val best = sc.indices.maxBy { sc[it] }
            val low = all.copyOfRange(best * LOW * LOW, (best + 1) * LOW * LOW)
            // The low-res mask covers the padded 1024² input; sample it bilinearly per pixel.
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
            if (bytes.none { it.toInt() != 0 }) null else ObjectSelect.Mask(bytes, w, h)
        }
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
        val ins = enc.createInputBuffers(); val outs = enc.createOutputBuffers()
        ins[0].writeFloat(input)
        enc.run(ins, outs)
        val emb = outs[0].readFloat()
        (ins + outs).forEach { it.close() }
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
