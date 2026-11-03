package io.github.nielstron.brushkin.ui

import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import io.github.nielstron.brushkin.engine.StrokePoint

/** A Bézier anchor: position plus incoming/outgoing handles (doc coordinates). */
data class Anchor(val p: PointF, val hin: PointF = PointF(p.x, p.y), val hout: PointF = PointF(p.x, p.y))

/** The path being edited by the Paths tool (GIMP's Paths tool, adapted to touch). */
class PathEdit {
    val anchors = ArrayList<Anchor>()
    var closed = false

    fun toPath(): Path {
        val path = Path()
        if (anchors.isEmpty()) return path
        path.moveTo(anchors[0].p.x, anchors[0].p.y)
        for (i in 1 until anchors.size) seg(path, anchors[i - 1], anchors[i])
        if (closed && anchors.size > 2) { seg(path, anchors.last(), anchors[0]); path.close() }
        return path
    }

    private fun seg(path: Path, a: Anchor, b: Anchor) = path.cubicTo(a.hout.x, a.hout.y, b.hin.x, b.hin.y, b.p.x, b.p.y)

    /** Points along the path every [step] doc pixels, for stroking with a brush. */
    fun sample(step: Float = 1f): List<StrokePoint> {
        val m = PathMeasure(toPath(), false)
        val out = ArrayList<StrokePoint>()
        val pos = FloatArray(2)
        do {
            var d = 0f
            while (d <= m.length) { m.getPosTan(d, pos, null); out.add(StrokePoint(pos[0], pos[1], 1f)); d += step }
        } while (m.nextContour())
        return out
    }

    fun clear() { anchors.clear(); closed = false }
}
