package io.github.nielstron.brushkin.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons on a 24-unit grid, matching the mockup. */
object ToolIcons {
    private val cache = HashMap<String, ImageVector>()

    fun stroke(d: String, width: Float = 1.7f): ImageVector = cache.getOrPut(d + width) {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            ).build()
    }

    val brush get() = stroke("M18 3l3 3-8.5 8.5-3-3zM9.5 11.5C6.5 11.5 5 13.5 5 16c0 1.5-1 3-2.5 3.5 3.5 1.5 8 .5 9-2 .6-1.6.3-3.6-2-6z")
    val pencil get() = stroke("M4 20l1-5L16 4l4 4L9 19zM14 6l4 4")
    val airbrush get() = stroke("M4 20l7-7M11 13l4-4 3 3-4 4zM17 4h.01M20 6h.01M19 2h.01")
    val eraser get() = stroke("M7 21h13M5 15l9-9 5 5-9 9H8z")
    val smudge get() = stroke("M8 13V5a2 2 0 0 1 4 0v6M12 10a2 2 0 0 1 4 0v2M16 11a2 2 0 0 1 4 0v4a7 7 0 0 1-7 7h-1a7 7 0 0 1-6-4l-2-4a2 2 0 0 1 3-2l1 2")
    val clone get() = stroke("M9 3h6v5l3 3v3H6v-3l3-3zM4 18h16M7 21h10")
    val heal get() = stroke("M4 15L15 4a3 3 0 0 1 5 5L9 20a3 3 0 0 1-5-5zM10 10l4 4")
    val dodge get() = stroke("M12 3a5 5 0 1 0 0 10a5 5 0 1 0 0-10M12 13v8")
    val blur get() = stroke("M12 3c3 4 6 7.5 6 11a6 6 0 0 1-12 0c0-3.5 3-7 6-11z")
    val scissors get() = stroke("M6 9a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 21a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM20 4L8.1 15.9M14.5 14.5L20 20M8.1 8.1L12 12")
    val foreground get() = stroke("M12 4a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM5 21c0-4 3-6 7-6s7 2 7 6M3 3h4M3 3v4M21 3h-4M21 3v4M3 21v-3M21 21v-3")
    val objectSel get() = stroke("M12 2l1.2 3.3L16.5 6.5l-3.3 1.2L12 11l-1.2-3.3L7.5 6.5l3.3-1.2zM5 13h14v8H5z")
    val paths get() = stroke("M5 17a2 2 0 1 0 0 4a2 2 0 1 0 0-4M19 3a2 2 0 1 0 0 4a2 2 0 1 0 0-4M7 19c7 0 3-14 10-14")
    val move get() = stroke("M12 2v20M2 12h20M9 5l3-3 3 3M9 19l3 3 3-3M5 9l-3 3 3 3M19 9l3 3-3 3")
    val rotate get() = stroke("M20 12a8 8 0 1 1-3-6.2M20 4v5h-5")
    val shear get() = stroke("M8 5h13l-5 14H3z")
    val perspective get() = stroke("M7 5h10l4 14H3zM12 5v14")
    val cube get() = stroke("M12 3l8 4.5v9L12 21l-8-4.5v-9zM12 12l8-4.5M12 12v9M12 12L4 7.5")
    val handle get() = stroke("M6 6h.01M18 6h.01M12 18h.01M6 6l6 12 6-12z")
    val warp get() = stroke("M3 12c3-6 6 6 9 0s6 6 9 0M3 6c3-4 6 4 9 0s6 4 9 0M3 18c3-4 6 4 9 0s6 4 9 0")
    val cage get() = stroke("M5 5l7 2 7-2-2 7 2 7-7-2-7 2 2-7z")
    val align get() = stroke("M4 3v18M8 7h12M8 17h8")
    val target get() = stroke("M12 3v6M12 15v6M3 12h6M15 12h6")
    val bucket get() = stroke("M4 12l7-7 7 7-7 7zM19.5 15c1 1.5 1.8 2.5 1.8 3.4a1.8 1.8 0 0 1-3.6 0c0-.9.8-1.9 1.8-3.4z")
    val gradient get() = stroke("M4 4h16v16H4zM4 14L14 4M4 20L20 4M10 20L20 10")
    val rect get() = stroke("M4 6h16v12H4z")
    val ellipse get() = stroke("M3 12a9 6 0 1 0 18 0a9 6 0 1 0-18 0")
    val lasso get() = stroke("M7 17c-3-1-4-4-3-7 2-5 12-6 15-2 2 3-1 7-6 7-2 0-4-.5-5-1M7 17l-1 4")
    val fuzzy get() = stroke("M4 20L14 10M15 3v3M19.5 4.5l-2 2M21 9h-3")
    val byColor get() = stroke("M4 4h7v7H4zM13 13h7v7h-7zM13 4h7v7h-7z")
    val transform get() = stroke("M3 7V3h4M17 3h4v4M21 17v4h-4M7 21H3v-4M8 8h8v8H8z")
    val crop get() = stroke("M6 2v16h16M2 6h16v16")
    val picker get() = stroke("M14.5 4.5l5 5-2 2-5-5zM12.5 8.5L4 17v3h3l8.5-8.5")
    val text get() = stroke("M5 5h14M12 5v14M9 19h6")
    val line get() = stroke("M5 19L19 5", 2f)
    val angle get() = stroke("M4 20h16M4 20L16 6")
    val layers get() = stroke("M12 3l9 5-9 5-9-5zM3 13l9 5 9-5")
    val sliders get() = stroke("M4 7h10M18 7h2M4 17h4M12 17h8M16 5v4M10 15v4")
    val ink get() = stroke("M12 2l4 7-4 13-4-13zM12 9v5M5 22h14")
    val mypaint get() = stroke("M4 20c4 0 5-3 5-5 0-3 3-4 5-2M14 13l6-9M8 20c2-1 4-1 6 0")
    val perspClone get() = stroke("M3 8l6-3v14l-6-3zM12 6l9-3v18l-9-3zM9 12h3")
    val mirror get() = stroke("M12 3v18M8 7L3 17h5zM16 7l5 10h-5z")

    fun of(t: Tool): ImageVector = when (t) {
        Tool.RECT_SELECT -> rect; Tool.ELLIPSE_SELECT -> ellipse; Tool.FREE_SELECT -> lasso
        Tool.FUZZY_SELECT -> fuzzy; Tool.COLOR_SELECT -> byColor; Tool.OBJECT_SELECT -> objectSel; Tool.SCISSORS -> scissors; Tool.FOREGROUND_SELECT -> foreground
        Tool.BRUSH -> brush; Tool.PENCIL -> pencil; Tool.AIRBRUSH -> airbrush; Tool.ERASER -> eraser
        Tool.SMUDGE -> smudge; Tool.CLONE -> clone; Tool.HEAL -> heal; Tool.DODGE_BURN -> dodge; Tool.BLUR_SHARPEN -> blur; Tool.INK -> ink; Tool.MYPAINT -> mypaint; Tool.PERSPECTIVE_CLONE -> perspClone; Tool.BUCKET -> bucket; Tool.GRADIENT -> gradient
        Tool.MOVE -> move; Tool.SCALE -> transform; Tool.ROTATE -> rotate; Tool.FLIP -> mirror; Tool.SHEAR -> shear; Tool.PERSPECTIVE -> perspective; Tool.TRANSFORM_3D -> cube; Tool.HANDLE -> handle; Tool.WARP -> warp; Tool.CAGE -> cage; Tool.ALIGN -> align; Tool.CROP -> crop; Tool.PICKER -> picker; Tool.TEXT -> text; Tool.PATHS -> paths
    }
}
