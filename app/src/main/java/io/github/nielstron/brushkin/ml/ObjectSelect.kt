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
                .enableMultipleSubjects(SubjectSegmenterOptions.SubjectResultOptions.Builder().enableConfidenceMask().build())
                .build()
        )
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
            val out = ByteArray(w * h)
            fun put(buf: java.nio.FloatBuffer, ox: Int, oy: Int, bw: Int, bh: Int) {
                buf.rewind()
                for (yy in 0 until bh) for (xx in 0 until bw) {
                    val c = buf.get()
                    // Sharpen ML confidence into a crisp but antialiased edge.
                    val v = ((c - 0.35f) / 0.3f).coerceIn(0f, 1f)
                    val i = (oy + yy) * w + ox + xx
                    val b = (v * 255).roundToInt()
                    if (b > (out[i].toInt() and 0xFF)) out[i] = b.toByte()
                }
            }
            if (x < 0) {
                val fg = result.foregroundConfidenceMask ?: return@withContext null
                put(fg, 0, 0, w, h)
            } else {
                // Pick the subject with the highest confidence at the tapped pixel.
                var best: com.google.mlkit.vision.segmentation.subject.Subject? = null
                var bestConf = 0.3f
                for (s in result.subjects) {
                    val m = s.confidenceMask ?: continue
                    val lx = x - s.startX; val ly = y - s.startY
                    if (lx !in 0 until s.width || ly !in 0 until s.height) continue
                    val c = m.get(ly * s.width + lx)
                    if (c > bestConf) { bestConf = c; best = s }
                }
                val s = best ?: return@withContext null
                put(s.confidenceMask!!, s.startX, s.startY, s.width, s.height)
            }
            Mask(out, w, h)
        }
    }

    fun close() = segmenter.close()
}
