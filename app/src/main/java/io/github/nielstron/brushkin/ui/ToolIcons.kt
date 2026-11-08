package io.github.nielstron.brushkin.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material Rounded icons, with custom glyphs only for specialized tools without a clear equivalent. */
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

    /** A checkerboard, the usual picture of transparency, for locking a layer's alpha. */
    val alphaLock: ImageVector by lazy {
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                pathData = PathParser().parsePathString("M2 2h5v5H2zM12 2h5v5h-5zM7 7h5v5H7zM17 7h5v5h-5zM2 12h5v5H2zM12 12h5v5h-5zM7 17h5v5H7zM17 17h5v5h-5z").toNodes(),
                fill = SolidColor(Color.White),
            )
            .addPath(
                pathData = PathParser().parsePathString("M2 2h20v20H2z").toNodes(),
                stroke = SolidColor(Color.White), strokeLineWidth = 1.2f,
            ).build()
    }

    val brush get() = Icons.Rounded.Brush
    val pencil get() = Icons.Rounded.Edit
    val airbrush get() = Icons.Rounded.Air
    val eraser get() = stroke("M7 21h13M5 15l9-9 5 5-9 9H8z")
    val smudge get() = Icons.Rounded.Gesture
    val clone get() = stroke("M9 3h6v5l3 3v3H6v-3l3-3zM4 18h16M7 21h10")
    val heal get() = Icons.Rounded.Healing
    val dodge get() = Icons.Rounded.Exposure
    val blur get() = Icons.Rounded.BlurOn
    val scissors get() = Icons.Rounded.ContentCut
    val objectSel get() = Icons.Rounded.AutoAwesome
    val removeObj get() = Icons.Rounded.AutoFixHigh
    val depthBlur get() = Icons.Rounded.CenterFocusStrong
    val upscale get() = Icons.Rounded.OpenInFull
    val denoise get() = Icons.Rounded.Grain
    val deblur get() = Icons.Rounded.FilterCenterFocus
    val curves get() = Icons.Rounded.ShowChart
    val contrast get() = Icons.Rounded.Contrast
    val hue get() = Icons.Rounded.Palette
    val sharpen get() = Icons.Rounded.ChangeHistory
    val levels get() = Icons.Rounded.BarChart
    val filterFx get() = Icons.Rounded.FilterAlt
    val alphaChecker get() = Icons.Rounded.GridOn
    val ripple get() = Icons.Rounded.Waves
    val meme get() = Icons.Rounded.TextFields
    val paths get() = Icons.Rounded.Timeline
    val move get() = Icons.Rounded.OpenWith
    val rotate get() = Icons.Rounded.RotateRight
    val shear get() = stroke("M8 5h13l-5 14H3z")
    val perspective get() = stroke("M7 5h10l4 14H3zM12 5v14")
    val cube get() = Icons.Rounded.ViewInAr
    val handle get() = stroke("M6 6h.01M18 6h.01M12 18h.01M6 6l6 12 6-12z")
    val warp get() = stroke("M3 12c3-6 6 6 9 0s6 6 9 0M3 6c3-4 6 4 9 0s6 4 9 0M3 18c3-4 6 4 9 0s6 4 9 0")
    val cage get() = stroke("M5 5l7 2 7-2-2 7 2 7-7-2-7 2 2-7z")
    val align get() = Icons.Rounded.FormatAlignLeft
    val target get() = Icons.Rounded.MyLocation
    val bucket get() = Icons.Rounded.FormatColorFill
    val gradient get() = Icons.Rounded.Gradient
    val rect get() = Icons.Rounded.CropLandscape
    val ellipse get() = Icons.Rounded.PanoramaFishEye
    val lasso get() = stroke("M7 17c-3-1-4-4-3-7 2-5 12-6 15-2 2 3-1 7-6 7-2 0-4-.5-5-1M7 17l-1 4")
    val fuzzy get() = Icons.Rounded.AutoFixNormal
    val byColor get() = Icons.Rounded.Palette
    val transform get() = Icons.Rounded.Transform
    val crop get() = Icons.Rounded.Crop
    val picker get() = Icons.Rounded.Colorize
    val text get() = Icons.Rounded.TextFields
    val line get() = Icons.Rounded.HorizontalRule
    val angle get() = Icons.Rounded.SquareFoot
    val layers get() = Icons.Rounded.Layers
    val sliders get() = Icons.Rounded.Tune
    val ink get() = Icons.Rounded.Create
    val mypaint get() = Icons.Rounded.Draw
    val perspClone get() = stroke("M3 8l6-3v14l-6-3zM12 6l9-3v18l-9-3zM9 12h3")
    val mirror get() = Icons.Rounded.Flip

    fun of(t: Tool): ImageVector = when (t) {
        Tool.RECT_SELECT -> rect; Tool.ELLIPSE_SELECT -> ellipse; Tool.FREE_SELECT -> lasso
        Tool.FUZZY_SELECT -> fuzzy; Tool.COLOR_SELECT -> byColor; Tool.OBJECT_SELECT -> objectSel; Tool.REMOVE_OBJECT -> removeObj; Tool.DEPTH_BLUR -> depthBlur; Tool.UPSCALE -> upscale; Tool.DENOISE -> denoise; Tool.DEBLUR -> deblur;
        Tool.CURVES -> curves; Tool.BRIGHTNESS_CONTRAST -> contrast; Tool.HUE_SATURATION -> hue; Tool.COLORIZE -> hue; Tool.COLOR_BALANCE -> hue
        Tool.GAUSSIAN_BLUR -> blur; Tool.MOTION_BLUR -> blur; Tool.NOISE_REDUCTION -> denoise; Tool.UNSHARP_MASK -> sharpen; Tool.LEVELS -> levels
        Tool.EXPOSURE -> dodge; Tool.SHADOWS_HIGHLIGHTS -> contrast; Tool.VIGNETTE -> depthBlur; Tool.DROP_SHADOW -> filterFx; Tool.PIXELIZE -> denoise; Tool.THRESHOLD -> contrast;
        Tool.RIPPLE -> ripple; Tool.WHIRL_PINCH -> warp; Tool.LENS_DISTORTION -> perspective; Tool.RGB_NOISE -> denoise
        Tool.COLOR_TO_ALPHA -> alphaChecker; Tool.CHANNEL_MIXER -> levels; Tool.POSTERIZE -> levels; Tool.SCISSORS -> scissors
        Tool.BRUSH -> brush; Tool.PENCIL -> pencil; Tool.AIRBRUSH -> airbrush; Tool.ERASER -> eraser
        Tool.SMUDGE -> smudge; Tool.CLONE -> clone; Tool.HEAL -> heal; Tool.DODGE_BURN -> dodge; Tool.BLUR_SHARPEN -> blur; Tool.INK -> ink; Tool.MYPAINT -> mypaint; Tool.PERSPECTIVE_CLONE -> perspClone; Tool.BUCKET -> bucket; Tool.GRADIENT -> gradient
        Tool.MOVE -> move; Tool.SCALE -> transform; Tool.FLIP -> mirror; Tool.SHEAR -> shear; Tool.PERSPECTIVE -> perspective; Tool.TRANSFORM_3D -> cube; Tool.HANDLE -> handle; Tool.WARP -> warp; Tool.CAGE -> cage; Tool.ALIGN -> align; Tool.CROP -> crop; Tool.PICKER -> picker; Tool.TEXT -> text; Tool.MEME -> meme; Tool.PATHS -> paths
    }
}
