package io.github.nielstron.brushkin

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.NativeGegl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs

/** Explicit benchmark bypass; normal rendering still uses the measured allowlist. */
class OpenClSurveyTest {
    @Test fun compareCandidates() {
        val args=InstrumentationRegistry.getArguments()
        val requireParity=args.getString("requireParity") == "true"
        val active=NativeGegl.isOpenClActive()
        if (requireParity) assertTrue("Requires an actual OpenCL GPU", active)
        else assumeTrue("Requires an actual OpenCL GPU", active)
        val w=args.getString("width", "1024").toInt()
        val h=args.getString("height", "768").toInt()
        val rounds=args.getString("rounds", "2").toInt()
        val selected=args.getString("operations", "").split(',')
        val bypassAllowlist=args.getString("normalRoute") != "true"
        val timeAlpha=args.getString("timeAlpha") == "true"
        val expectAlphaOpenCl=args.getString("expectAlphaOpenCl") != "false"
        val configurations=linkedMapOf(
            "channel-mixer" to mapOf("rr-gain" to 0.7,"rg-gain" to 0.3,"bb-gain" to 0.8),
            "alien-map" to mapOf("cpn-1-frequency" to 1.3,"cpn-2-frequency" to 0.8),
            "brightness-contrast" to mapOf("brightness" to 0.1,"contrast" to 1.2),
            "color-temperature" to mapOf("original-temperature" to 6500.0,"intended-temperature" to 4500.0),
            "contrast-curve" to mapOf("sampling-points" to 256.0),
            "gray" to emptyMap(),
            "hue-chroma" to mapOf("hue" to 30.0,"chroma" to 10.0,"lightness" to 5.0),
            "invert-linear" to emptyMap(),
            "levels" to mapOf("in-low" to 0.05,"in-high" to 0.9),
            "mono-mixer" to mapOf("red" to 0.5,"green" to 0.3,"blue" to 0.2),
            "noise-hsv" to mapOf("seed" to 42.0,"hue-distance" to 12.0),
            "noise-hurl" to mapOf("seed" to 42.0,"pct-random" to 30.0),
            "opacity" to mapOf("value" to 0.6),
            "posterize" to mapOf("levels" to 6.0),
            "stretch-contrast" to emptyMap(),
            "threshold" to mapOf("value" to 0.3,"high" to 0.8),
            "value-invert" to emptyMap(),
            "vignette" to mapOf("radius" to 0.8),
            "color-exchange" to mapOf("red-threshold" to 0.3,"green-threshold" to 0.3,"blue-threshold" to 0.3),
            "color-to-alpha" to emptyMap(),
            "edge-laplace" to emptyMap(),
            "gaussian-blur-selective" to mapOf("blur-radius" to 4.0,"max-delta" to 0.2),
            "motion-blur-circular" to mapOf("angle" to 5.0),
            "oilify" to mapOf("mask-radius" to 4.0),
            "red-eye-removal" to mapOf("threshold" to 0.5),
            "shadows-highlights-correction" to mapOf("shadows" to 20.0,"highlights" to -20.0),
            "texturize-canvas" to mapOf("depth" to 4.0),
            "video-degradation" to emptyMap(),
            "weighted-blend" to emptyMap(),
            "svg:src-over" to emptyMap(),
            "checkerboard" to mapOf("x" to 16.0,"y" to 16.0),
            "cell-noise" to mapOf("seed" to 42.0,"scale" to 2.0),
            "simplex-noise" to mapOf("seed" to 42.0,"iterations" to 3.0),
            "diffraction-patterns" to mapOf("width" to w.toDouble(),"height" to h.toDouble()),
            "gblur-1d#fir" to mapOf("std-dev" to 4.0,"filter" to 1.0),
            "gblur-1d#auto" to mapOf("std-dev" to 4.0,"filter" to 0.0),
            "gaussian-blur#fir" to mapOf("std-dev-x" to 4.0,"std-dev-y" to 4.0,"filter" to 1.0),
            "gaussian-blur#auto" to mapOf("std-dev-x" to 4.0,"std-dev-y" to 4.0,"filter" to 0.0),
            "gaussian-blur#small-auto" to mapOf("std-dev-x" to 0.5,"std-dev-y" to 0.5,"filter" to 0.0),
            "exposure" to mapOf("exposure" to 0.7,"black-level" to 0.01),
            "motion-blur-linear" to mapOf("length" to 20.0,"angle" to 30.0),
            "edge-sobel#both" to mapOf("horizontal" to 1.0,"vertical" to 1.0,"keep-sign" to 1.0),
            "box-blur" to mapOf("radius" to 8.0),
            "bilateral-filter" to mapOf("blur-radius" to 4.0,"edge-preservation" to 8.0),
            "snn-mean" to mapOf("radius" to 4.0,"pairs" to 2.0),
            "noise-reduction" to mapOf("iterations" to 4.0),
            "edge-sobel" to mapOf("horizontal" to 1.0,"vertical" to 0.0,"keep-sign" to 1.0),
            "pixelize" to mapOf("size-x" to 16.0,"size-y" to 16.0),
            "c2g" to mapOf("radius" to 100.0,"samples" to 4.0,"iterations" to 10.0),
            "newsprint" to emptyMap(),
            // Kernels added or fixed in the gegl-opencl fork.
            "edge-sobel#abs" to mapOf("horizontal" to 1.0,"vertical" to 1.0,"keep-sign" to 0.0),
            "whirl-pinch" to mapOf("whirl" to 90.0,"pinch" to 0.3),
            "polar-coordinates" to emptyMap(),
            "ripple" to mapOf("amplitude" to 10.0,"period" to 50.0),
            "waves" to emptyMap(),
            "lens-distortion" to mapOf("main" to 20.0,"edge" to 10.0,"zoom" to 10.0),
            "median-blur" to mapOf("radius" to 5.0),
            "mean-curvature-blur" to emptyMap(),
            "motion-blur-zoom" to emptyMap(),
            "lens-blur" to emptyMap(),
            "focus-blur#gaussian" to emptyMap(),
            "focus-blur#lens" to mapOf("blur-type" to 1.0),
            "noise-rgb" to mapOf("seed" to 42.0),
            "noise-cie-lch" to mapOf("seed" to 42.0),
            "noise-pick" to mapOf("seed" to 42.0),
            "noise-slur" to mapOf("seed" to 42.0),
            "noise-spread" to mapOf("seed" to 42.0),
            "stretch-contrast-hsv" to emptyMap(),
            "normal-map" to emptyMap(),
            "color-enhance" to emptyMap(),
            "emboss" to emptyMap(),
            "deinterlace" to emptyMap(),
            "unsharp-mask" to emptyMap(),
            "high-pass" to emptyMap(),
            "softglow" to emptyMap(),
            "cartoon" to emptyMap(),
            "photocopy" to emptyMap(),
            "bloom" to emptyMap(),
            "shadows-highlights" to mapOf("shadows" to 20.0,"highlights" to -20.0),
        )
        for((name,params) in configurations) {
            if(selected != listOf("") && name !in selected) continue
            val operation=name.substringBefore("#")
            val op=if(":" in operation) operation else "gegl:$operation"
            val keys=params.keys.toTypedArray(); val values=params.values.toDoubleArray()
            Log.i("OpenClSurvey", "START ${w}x${h} $name params=$params bypassAllowlist=$bypassAllowlist")
            fun times(alpha: Boolean): Pair<List<Double>, List<Double>> {
                val cpuTimes=mutableListOf<Double>(); val gpuTimes=mutableListOf<Double>()
                val pixels=makePixels(w,h,alpha)
                repeat(rounds) { round ->
                    for(gpu in if(round%2==0) listOf(false,true) else listOf(true,false)) {
                        val start=System.nanoTime()
                        NativeGegl.render(pixels,w,h,op,keys,values,gpu,bypassAllowlist)
                        (if(gpu) gpuTimes else cpuTimes).add((System.nanoTime()-start)/1e6)
                        if (requireParity) assertEquals("$op alpha=$alpha round=$round",
                            gpu && (!alpha || expectAlphaOpenCl), NativeGegl.lastRenderUsedOpenCl())
                    }
                }
                return cpuTimes to gpuTimes
            }
            val opaque=compare(w,h,op,keys,values,false,bypassAllowlist,requireParity,true)
            val (cpuTimes,gpuTimes)=times(false)
            val alpha=compare(w,h,op,keys,values,true,bypassAllowlist,requireParity,expectAlphaOpenCl)
            val alphaTimes=if (timeAlpha) {
                val (cpu,gpu)=times(true)
                " cpuAlpha=$cpu gpuAlpha=$gpu"
            } else ""
            Log.i("OpenClSurvey", "RESULT ${w}x${h} $name cpu=$cpuTimes gpu=$gpuTimes opaque=$opaque alpha=$alpha$alphaTimes")
        }
    }

    private fun makePixels(w:Int,h:Int,alpha:Boolean)=IntArray(w*h) { i ->
        ((if(alpha) i%256 else 255) shl 24) or ((i%251) shl 16) or ((i/w%253) shl 8) or ((i*7)%255)
    }

    private fun compare(w:Int,h:Int,op:String,keys:Array<String>,values:DoubleArray,alpha:Boolean,
                        bypassAllowlist:Boolean,requireParity:Boolean,expectOpenCl:Boolean):String {
        val pixels=makePixels(w,h,alpha)
        val cpu=NativeGegl.render(pixels,w,h,op,keys,values,false,bypassAllowlist)
        val gpu=NativeGegl.render(pixels,w,h,op,keys,values,true,bypassAllowlist)
        val used=NativeGegl.lastRenderUsedOpenCl()
        var maximum=0.0; var total=0.0; var aboveTwo=0L
        val shifts=intArrayOf(0,8,16,24)
        for(i in cpu.indices) for(shift in shifts) {
            val a=if(alpha && shift!=24) cpu[i] ushr 24 else 255
            val b=if(alpha && shift!=24) gpu[i] ushr 24 else 255
            val error=abs((cpu[i] ushr shift and 255)*a-(gpu[i] ushr shift and 255)*b)/255.0
            maximum=maxOf(maximum,error); total+=error
            if(error>2) aboveTwo++
        }
        if (requireParity) assertTrue("$op ${w}x$h alpha=$alpha usedOpenCl=$used maxError=$maximum",
            used == expectOpenCl && maximum <= 1.0)
        return "{used=$used,max=$maximum,mean=${total/(cpu.size*4L)},above2=$aboveTwo}"
    }
}
