package io.github.nielstron.brushkin.ml

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Tap-to-select objects with ML Kit subject segmentation. The model is provided and updated by
 * Google Play services, so it adds nothing to the APK.
 */
class ObjectSelect {
    private val segmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }

    /**
     * Make sure Play services has the segmentation module, requesting the download if needed
     * (installs from adb or sideloads skip the install-time download).
     */
    suspend fun ensureModule(context: android.content.Context) {
        val client = com.google.android.gms.common.moduleinstall.ModuleInstall.getClient(context)
        if (client.areModulesAvailable(segmenter).await().areModulesAvailable()) return
        client.installModules(
            com.google.android.gms.common.moduleinstall.ModuleInstallRequest.newBuilder().addApi(segmenter).build()
        ).await()
        repeat(240) {
            if (client.areModulesAvailable(segmenter).await().areModulesAvailable()) return
            kotlinx.coroutines.delay(500)
        }
        throw IllegalStateException("The object selection model is still downloading")
    }

    /** Result mask at the size of the input image, 0–255 per pixel. */
    class Mask(val bytes: ByteArray, val width: Int, val height: Int)

    /**
     * Segments [image] and returns the subject under (x, y) (image pixels), or the whole
     * foreground when [x] is negative. Null when nothing is there.
     */
    suspend fun select(image: Bitmap, x: Int, y: Int): Mask? {
        val result = segmenter.process(InputImage.fromBitmap(image, 0)).await()
        return withContext(Dispatchers.Default) {
            val w = image.width; val h = image.height
            val fg = result.foregroundConfidenceMask ?: return@withContext null
            fg.rewind()
            val conf = FloatArray(w * h).also { fg.get(it) }
            // Sharpen ML confidence into a crisp but antialiased edge.
            fun soft(c: Float) = (((c - 0.35f) / 0.3f).coerceIn(0f, 1f) * 255).roundToInt()
            val out = ByteArray(w * h)
            if (x < 0) {
                for (i in out.indices) out[i] = soft(conf[i]).toByte()
                return@withContext Mask(out, w, h)
            }
            // ML Kit's per-subject masks crash its GPU runner (16.0.0-beta1), so the tapped object
            // is isolated from the foreground mask as the connected region under the finger.
            val inside = BooleanArray(w * h) { conf[it] > 0.15f }
            var seed = -1
            val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
            val reach = maxOf(w, h) / 20
            var best = Int.MAX_VALUE
            for (dy in -reach..reach) for (dx in -reach..reach) {
                val px = cx + dx; val py = cy + dy
                if (px !in 0 until w || py !in 0 until h || !inside[py * w + px]) continue
                val d = dx * dx + dy * dy
                if (d < best) { best = d; seed = py * w + px }
            }
            if (seed < 0) return@withContext null
            val stack = IntArray(w * h); var sp = 0
            val seen = BooleanArray(w * h)
            stack[sp++] = seed; seen[seed] = true
            while (sp > 0) {
                val i = stack[--sp]
                out[i] = soft(conf[i]).toByte()
                val px = i % w; val py = i / w
                if (px > 0 && inside[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (px < w - 1 && inside[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (py > 0 && inside[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (py < h - 1 && inside[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            Mask(out, w, h)
        }
    }

    fun close() = segmenter.close()
}
