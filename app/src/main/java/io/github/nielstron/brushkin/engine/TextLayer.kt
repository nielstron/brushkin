package io.github.nielstron.brushkin.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject

/**
 * What an editable text layer is made of. Its pixels are always re-rendered from this, so the text
 * stays crisp and editable through moves, scales and rotations (like GIMP text layers).
 */
data class TextSpec(
    val text: String,
    val size: Float,
    /** Font family name as understood by Typeface.create ("sans-serif", "serif", "cursive", …). */
    val family: String = "sans-serif",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    val outline: Boolean = false,
    /** Text box → doc transform. */
    val matrix: FloatArray = Matrix().let { FloatArray(9).also { v -> it.getValues(v) } },
    /** Text on a path: Bézier anchors as (x, y, inX, inY, outX, outY)*, in doc coordinates. */
    val path: FloatArray? = null,
    val pathClosed: Boolean = false,
) {
    val typeface: Typeface get() = Typeface.create(family, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))

    fun paint() = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = this@TextSpec.typeface; textSize = size; color = this@TextSpec.color }

    fun matrix() = Matrix().apply { setValues(matrix) }

    fun withMatrix(m: Matrix) = copy(matrix = FloatArray(9).also { m.getValues(it) })

    /** The text rendered unpositioned (for placing as a floating layer). */
    fun renderBox(): Bitmap {
        val p = paint()
        val lines = text.split('\n')
        val fm = p.fontMetrics
        val lineH = fm.descent - fm.ascent
        val pad = size * 0.15f
        val w = (lines.maxOf { p.measureText(it) } + pad * 2).toInt().coerceAtLeast(1)
        val h = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        lines.forEachIndexed { i, line ->
            val y = pad - fm.ascent + i * lineH
            if (outline) c.drawText(line, pad, y, outlinePaint(p))
            c.drawText(line, pad, y, p)
        }
        return bmp
    }

    private fun outlinePaint(p: Paint) = Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.08f; color = android.graphics.Color.WHITE; strokeJoin = Paint.Join.ROUND }

    fun bezierPath(): Path? {
        val a = path ?: return null
        val n = a.size / 6
        if (n < 2) return null
        val p = Path()
        p.moveTo(a[0], a[1])
        fun seg(i: Int, j: Int) = p.cubicTo(a[i * 6 + 4], a[i * 6 + 5], a[j * 6 + 2], a[j * 6 + 3], a[j * 6], a[j * 6 + 1])
        for (i in 1 until n) seg(i - 1, i)
        if (pathClosed && n > 2) { seg(n - 1, 0); p.close() }
        return p
    }

    /** Render into a full-canvas layer. */
    fun renderLayer(w: Int, h: Int): TiledBitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bp = bezierPath()
        if (bp != null) {
            val p = paint()
            val line = text.replace('\n', ' ')
            if (outline) c.drawTextOnPath(line, bp, 0f, 0f, outlinePaint(p))
            c.drawTextOnPath(line, bp, 0f, 0f, p)
        } else {
            c.drawBitmap(renderBox(), matrix(), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        return TiledBitmap.fromBitmap(out)
    }

    fun toJson(): String = JSONObject().apply {
        put("text", text); put("size", size.toDouble()); put("family", family); put("bold", bold); put("italic", italic)
        put("color", color); put("outline", outline); put("matrix", JSONArray(matrix.map { it.toDouble() }))
        path?.let { put("path", JSONArray(it.map { v -> v.toDouble() })) }; put("pathClosed", pathClosed)
    }.toString()

    override fun equals(other: Any?) = other is TextSpec && toJson() == other.toJson()
    override fun hashCode() = toJson().hashCode()

    companion object {
        fun fromJson(s: String): TextSpec? = runCatching {
            val o = JSONObject(s)
            fun floats(a: JSONArray) = FloatArray(a.length()) { a.getDouble(it).toFloat() }
            TextSpec(
                o.getString("text"), o.getDouble("size").toFloat(), o.optString("family", "sans-serif"), o.optBoolean("bold"), o.optBoolean("italic"),
                o.optInt("color", 0xFF000000.toInt()), o.optBoolean("outline"), floats(o.getJSONArray("matrix")),
                o.optJSONArray("path")?.let { floats(it) }, o.optBoolean("pathClosed"),
            )
        }.getOrNull()
    }
}
