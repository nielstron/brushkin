package io.github.nielstron.brushkin.ml

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A model file attached to one of the project's GitHub releases, pinned by size and SHA-256. */
class ModelFile(val release: String, val name: String, val size: Long, val sha256: String) {
    val url get() = "https://github.com/nielstron/brushkin/releases/download/$release/$name"
}

/** Thrown when a model can't be used (not downloaded, download failed, corrupt). The message is for the user. */
class ModelUnavailable(msg: String) : Exception(msg)

/**
 * On-device models: none ship in the APK. Each is downloaded from the project's GitHub release the
 * first time a tool needs it (after the user agreed), verified against its pinned hash
 * (tools/export_*.py reproduce the files), and kept in the cache directory, so clearing the app's
 * cache frees the space.
 */
class ModelStore(private val context: Context) {
    private val root = File(context.cacheDir, "models")

    // Folder per release, e.g. cache/models/mobilesam-2 (where earlier versions kept that model).
    fun file(m: ModelFile) = File(File(root, m.release.removePrefix("models-")), m.name)

    fun ready(files: List<ModelFile>) = files.all { file(it).length() == it.size }

    /** Bytes still to download for [files]. */
    fun missingBytes(files: List<ModelFile>) = files.filter { file(it).length() != it.size }.sumOf { it.size }

    /** Downloads what's missing of [files]; [progress] gets 0…1. Every file is verified before it's kept. */
    suspend fun download(files: List<ModelFile>, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val todo = files.filter { file(it).length() != it.size }
        val total = todo.sumOf { it.size }.toFloat().coerceAtLeast(1f)
        var done = 0L
        for (m in todo) {
            val target = file(m).apply { parentFile?.mkdirs() }
            val part = File(target.parentFile, "${m.name}.part")
            val md = MessageDigest.getInstance("SHA-256")
            var url = URL(m.url)
            var conn: HttpURLConnection
            // Follow GitHub's redirect to its download host (HttpURLConnection won't switch hosts).
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply { instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000 }
                if (conn.responseCode in 300..399) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                break
            }
            if (conn.responseCode != 200) throw ModelUnavailable("Download failed (HTTP ${conn.responseCode})")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive() // cancelled by the user
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        progress((done / total).coerceAtMost(1f))
                    }
                }
            }
            val hex = md.digest().joinToString("") { "%02x".format(it) }
            if (hex != m.sha256) { part.delete(); throw ModelUnavailable("The downloaded model is corrupt; try again") }
            part.renameTo(target)
        }
        progress(1f)
    }

    // ------------------------------------------------------------------ running

    private var env: Environment? = null
    fun env(): Environment = env ?: Environment.create(context).also { env = it }

    /** Whether this device has a GPU LiteRT can use. */
    val hasGpu: Boolean by lazy { runCatching<Boolean> { env().getAvailableAccelerators().contains(Accelerator.GPU) }.getOrDefault(false) }

    /**
     * Compiles [m] for the GPU (float32: several models overflow in float16) with a program cache,
     * falling back to the CPU when there's no GPU or the model doesn't compile for it.
     * Returns the model and the accelerator it runs on.
     */
    fun compile(m: ModelFile, gpu: Boolean = true): Pair<CompiledModel, String> {
        if (!ready(listOf(m))) throw ModelUnavailable("The model isn't downloaded")
        val path = file(m).absolutePath
        if (gpu && hasGpu) runCatching {
            val cache = File(root, "gpu-cache").apply { mkdirs() }
            val opts = CompiledModel.Options(Accelerator.GPU).apply {
                gpuOptions = CompiledModel.GpuOptions(precision = CompiledModel.GpuOptions.Precision.FP32,
                    serializationDir = cache.absolutePath, modelCacheKey = m.sha256.take(16), serializeProgramCache = true)
            }
            return CompiledModel.create(path, opts, env()) to "GPU"
        }.onFailure { android.util.Log.w("Brushkin", "${m.name}: GPU unavailable (${it.message}), using CPU") }
        return CompiledModel.create(path, cpuOptions(), env()) to "CPU"
    }

    fun cpuOptions() = CompiledModel.Options(Accelerator.CPU).apply {
        cpuOptions = CompiledModel.CpuOptions(numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
    }
}

/** Runs the model once on [inputs] (of [signature], else the first); returns every output. */
fun CompiledModel.runOnce(vararg inputs: FloatArray, signature: String? = null): List<FloatArray> {
    val ins = if (signature != null) createInputBuffers(signature) else createInputBuffers()
    val outs = if (signature != null) createOutputBuffers(signature) else createOutputBuffers()
    try {
        inputs.forEachIndexed { i, x -> ins[i].writeFloat(x) }
        if (signature != null) run(ins, outs, signature) else run(ins, outs)
        return outs.map { it.readFloat() }
    } finally { (ins + outs).forEach { it.close() } }
}
