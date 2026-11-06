package io.github.nielstron.brushkin

import androidx.test.platform.app.InstrumentationRegistry
import io.github.nielstron.brushkin.engine.NativeGegl
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class OpenClPrototypeTest {
    @Test fun prototypeKernelsMatchCpuAndMeasureEndToEndCost() {
        val required=InstrumentationRegistry.getArguments().getString("requireOpenCl") == "true"
        val active=NativeGegl.isOpenClActive()
        android.util.Log.i("OpenClPrototype","GPU available=$active")
        if(required) assertTrue("Phone must actually initialize an OpenCL GPU",active)
        val args=InstrumentationRegistry.getArguments()
        val w=args.getString("width", "1024").toInt(); val h=args.getString("height", "768").toInt()
        android.util.Log.i("OpenClPrototype", "Starting ${w}x${h}")
        val pixels=IntArray(w*h) { i -> 0xff000000.toInt() or ((i%251) shl 16) or ((i/w%253) shl 8) or ((i*7)%255) }
        val configurations=listOf(
            "gegl:box-blur" to mapOf("radius" to 8.0),
            "gegl:bilateral-filter" to mapOf("blur-radius" to 4.0,"edge-preservation" to 8.0),
            "gegl:pixelize" to mapOf("size-x" to 16.0,"size-y" to 16.0),
            "gegl:edge-sobel" to mapOf("horizontal" to 1.0,"vertical" to 0.0,"keep-sign" to 1.0),
            "gegl:exposure" to mapOf("exposure" to 0.7,"black-level" to 0.01),
            "gegl:motion-blur-linear" to mapOf("length" to 20.0,"angle" to 30.0),
        )
        for ((op,parameters) in configurations) {
            val keys=parameters.keys.toTypedArray(); val values=parameters.values.toDoubleArray()
            fun run(gpu:Boolean, input:IntArray=pixels)=NativeGegl.render(input,w,h,op,keys,values,gpu)
            val maximum=verifyOpaque(w,h,op,keys,values,pixels,active)
            val timings=mutableMapOf<Boolean,MutableList<Double>>(false to mutableListOf(),true to mutableListOf())
            repeat(4) { round ->
                for(mode in if(round%2==0) listOf(false,true) else listOf(true,false)) {
                    val start=System.nanoTime(); run(mode)
                    timings.getValue(mode).add((System.nanoTime()-start)/1e6)
                }
            }
            val premultipliedError=verifyAlpha(w,h,op,keys,values,pixels)
            android.util.Log.i("OpenClPrototype","${w}x${h} $op cpuSamples=${timings.getValue(false)} gpuSamples=${timings.getValue(true)} cpuMs=${timings.getValue(false).average()} gpuMs=${timings.getValue(true).average()} maxError=$maximum alphaError=$premultipliedError")
        }
    }

    @Test fun additionalMeasuredKernelsMatchCpu() {
        val active=NativeGegl.isOpenClActive()
        if(InstrumentationRegistry.getArguments().getString("requireOpenCl") == "true") assertTrue(active)
        val w=1024; val h=768
        val pixels=IntArray(w*h) { i -> 0xff000000.toInt() or ((i%251) shl 16) or ((i/w%253) shl 8) or ((i*7)%255) }
        val configurations=listOf(
            "gegl:alien-map" to mapOf("cpn-1-frequency" to 1.3,"cpn-2-frequency" to 0.8),
            "gegl:noise-hsv" to mapOf("seed" to 42.0,"hue-distance" to 12.0),
            "gegl:noise-hurl" to mapOf("seed" to 42.0,"pct-random" to 30.0),
            "gegl:vignette" to mapOf("radius" to 0.8),
            "gegl:edge-laplace" to emptyMap(),
            "gegl:gaussian-blur-selective" to mapOf("blur-radius" to 4.0,"max-delta" to 0.2),
            "gegl:oilify" to mapOf("mask-radius" to 4.0),
            "gegl:cell-noise" to mapOf("seed" to 42.0,"scale" to 2.0),
            "gegl:simplex-noise" to mapOf("seed" to 42.0,"iterations" to 3.0),
            "gegl:diffraction-patterns" to mapOf("width" to w.toDouble(),"height" to h.toDouble()),
            "gegl:gblur-1d" to mapOf("std-dev" to 4.0,"filter" to 1.0),
            "gegl:gaussian-blur" to mapOf("std-dev-x" to 4.0,"std-dev-y" to 4.0,"filter" to 1.0),
            "gegl:gaussian-blur" to mapOf("std-dev-x" to 0.5,"std-dev-y" to 0.5,"filter" to 0.0),
        )
        for((op,params) in configurations) {
            val keys=params.keys.toTypedArray(); val values=params.values.toDoubleArray()
            verifyOpaque(w,h,op,keys,values,pixels,active)
            verifyAlpha(w,h,op,keys,values,pixels)
        }
        // Automatic Gaussian at sigma 4 keeps GEGL's IIR selection, which now runs on the GPU.
        val keys=arrayOf("std-dev-x","std-dev-y","filter")
        val values=doubleArrayOf(4.0,4.0,0.0)
        verifyOpaque(w,h,"gegl:gaussian-blur",keys,values,pixels,active)
        verifyAlpha(w,h,"gegl:gaussian-blur",keys,values,pixels)
    }

    // Kernels that work but were under 1.5x the 2-thread CPU path on the Pixel 6 Pro survey.
    private val cpuOnly=setOf("gegl:alien-map","gegl:noise-hurl","gegl:cell-noise")

    private fun verifyOpaque(w:Int,h:Int,op:String,keys:Array<String>,values:DoubleArray,pixels:IntArray,active:Boolean):Int {
        val cpu=NativeGegl.render(pixels,w,h,op,keys,values,false)
        assertFalse("Forced CPU path must not use OpenCL",NativeGegl.lastRenderUsedOpenCl())
        val gpu=NativeGegl.render(pixels,w,h,op,keys,values,true)
        assertEquals("$op must follow the measured OpenCL allowlist",active && op !in cpuOnly,NativeGegl.lastRenderUsedOpenCl())
        var maximum=0
        val shifts=intArrayOf(0,8,16,24)
        for(i in cpu.indices) for(shift in shifts) maximum=maxOf(maximum,abs((cpu[i] ushr shift and 255)-(gpu[i] ushr shift and 255)))
        assertTrue("$op differs from CPU by $maximum levels",maximum<=2)
        return maximum
    }

    private fun verifyAlpha(w:Int,h:Int,op:String,keys:Array<String>,values:DoubleArray,pixels:IntArray):Double {
        val transparent=IntArray(pixels.size) { i -> (pixels[i] and 0xffffff) or ((i%256) shl 24) }
        val cpu=NativeGegl.render(transparent,w,h,op,keys,values,false)
        val gpu=NativeGegl.render(transparent,w,h,op,keys,values,true)
        var maximum=0.0
        val shifts=intArrayOf(0,8,16)
        for(i in cpu.indices) {
            val a=cpu[i] ushr 24; val b=gpu[i] ushr 24
            maximum=maxOf(maximum,abs(a-b).toDouble())
            for(shift in shifts) maximum=maxOf(maximum,
                abs((cpu[i] ushr shift and 255)*a/255.0-(gpu[i] ushr shift and 255)*b/255.0))
        }
        assertTrue("$op transparency differs by $maximum",maximum<=2.0)
        return maximum
    }
}
