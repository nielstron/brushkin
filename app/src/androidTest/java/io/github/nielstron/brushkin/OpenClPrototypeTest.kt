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
            "gegl:bevel" to emptyMap(),
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

    @Test fun bevelUsesCpuForOneColumnAndUnverifiedParameters() {
        val active = NativeGegl.isOpenClActive()
        if (InstrumentationRegistry.getArguments().getString("requireOpenCl") == "true") assertTrue(active)
        val operation = "gegl:bevel"
        val height = 67
        val cases = listOf(
            1 to emptyMap(),
            97 to mapOf("blendmode" to 0.0, "elevation" to 75.0),
        )
        for ((width, parameters) in cases) {
            val keys = parameters.keys.toTypedArray()
            val values = parameters.values.toDoubleArray()
            val pixels = IntArray(width * height) { i ->
                ((i % 256) shl 24) or ((i % 251) shl 16) or ((i / width % 253) shl 8) or ((i * 7) % 255)
            }
            val expected = NativeGegl.render(pixels, width, height, operation, keys, values, false)
            fun checkFallback(path: String, actual: IntArray) {
                val description = "$path at ${width}x$height with $parameters"
                assertFalse("$description must use CPU", NativeGegl.lastRenderUsedOpenCl())
                assertArrayEquals(description, expected, actual)
            }
            checkFallback("array", NativeGegl.render(pixels, width, height, operation, keys, values, true))
            checkFallback("legacy", NativeGegl.renderLegacy(pixels, width, height, operation, keys, values, true))

            val buffer = NativeGegl.createBuffer(width, height)
            try {
                NativeGegl.uploadBuffer(buffer, 0, 0, width, height, pixels)
                val rendered = NativeGegl.renderBuffer(buffer, operation, keys, values, true, false)
                try {
                    val actual = IntArray(pixels.size)
                    NativeGegl.readRendered(rendered, 0, 0, width, height, actual)
                    checkFallback("buffer", actual)
                } finally {
                    NativeGegl.destroyRendered(rendered)
                }
            } finally {
                NativeGegl.destroyBuffer(buffer)
            }

            val session = NativeGegl.createSession(width, height, operation, keys, values, false)
            try {
                NativeGegl.updateSession(session, 0, 0, width, height, pixels)
                assertArrayEquals(intArrayOf(0, 0, width, height), NativeGegl.renderSession(session))
                val actual = IntArray(pixels.size)
                NativeGegl.readSession(session, 0, 0, width, height, actual)
                checkFallback("session", actual)
            } finally {
                NativeGegl.destroySession(session)
            }
        }
    }

    @Test fun newlyEnabledFiltersMatchCpu() {
        val active = NativeGegl.isOpenClActive()
        if (InstrumentationRegistry.getArguments().getString("requireOpenCl") == "true") assertTrue(active)
        val width = 1024
        val height = 768
        val pixels = IntArray(width * height) { i ->
            0xff000000.toInt() or ((i % 251) shl 16) or ((i / width % 253) shl 8) or ((i * 7) % 255)
        }
        val configurations = listOf(
            "gegl:color-rotate" to mapOf("dest-from" to 120.0, "dest-to" to 210.0),
            "gegl:convolution-matrix" to mapOf("c3" to 5.0, "c2" to -1.0, "c4" to -1.0, "b3" to -1.0, "d3" to -1.0),
            "gegl:denoise-dct" to emptyMap(),
            "gegl:domain-transform" to emptyMap(),
            "gegl:edge" to emptyMap(),
            "gegl:focus-blur" to emptyMap(),
            "gegl:focus-blur" to mapOf("blur-type" to 0.0),
            "gegl:fractal-trace" to emptyMap(),
            "gegl:image-gradient" to emptyMap(),
            "gegl:invert-gamma" to emptyMap(),
            "gegl:lens-flare" to emptyMap(),
            "gegl:saturation" to mapOf("scale" to 1.5),
            "gegl:sepia" to emptyMap(),
            "gegl:supernova" to mapOf("seed" to 42.0),
        )
        for ((op, parameters) in configurations) {
            val keys = parameters.keys.toTypedArray()
            val values = parameters.values.toDoubleArray()
            val opaque = verifyOpaque(width, height, op, keys, values, pixels, active)
            val alpha = verifyAlpha(width, height, op, keys, values, pixels)
            val usedOpenCl = NativeGegl.lastRenderUsedOpenCl()
            assertEquals("$op with $parameters must also use OpenCL on transparency", active, usedOpenCl)
            assertTrue("$op with $parameters differs by $opaque opaque and $alpha alpha-weighted levels", opaque <= 1 && alpha <= 1.0)
            android.util.Log.i("OpenClPrototype", "$op $parameters OpenCL=$usedOpenCl maxError=$opaque alphaError=$alpha")
        }
    }

    @Test fun focusBlurLensFollowsMeasuredOpenClPolicy() {
        val active = NativeGegl.isOpenClActive()
        if (InstrumentationRegistry.getArguments().getString("requireOpenCl") == "true") assertTrue(active)
        val lens = mapOf("blur-type" to 1.0)
        val explicitDefaults = lens + mapOf(
            "blur-radius" to 25.0, "highlight-factor" to 0.0, "highlight-threshold-low" to 0.0,
            "highlight-threshold-high" to 1.0, "shape" to 0.0, "x" to 0.5, "y" to 0.5,
            "radius" to 0.75, "focus" to 0.25, "midpoint" to 0.5, "aspect-ratio" to 0.0,
            "rotation" to 0.0, "high-quality" to 0.0,
        )
        val cases = listOf(
            Triple(512 to 384, lens, true),
            Triple(768 to 512, lens, true),
            Triple(1024 to 768, explicitDefaults, true),
            Triple(384 to 512, lens, false),
            Triple(512 to 383, lens, false),
            Triple(512 to 512, lens, false),
            Triple(800 to 384, lens, false),
            Triple(512 to 384, lens + ("blur-radius" to 33.0), false),
        )
        for ((extent, parameters, eligible) in cases) {
            val (width, height) = extent
            val keys = parameters.keys.toTypedArray()
            val values = parameters.values.toDoubleArray()
            val op = "gegl:focus-blur"
            val buffer = NativeGegl.createBuffer(width, height)
            val session = NativeGegl.createSession(width, height, op, keys, values, false)
            try {
                for (alpha in if (eligible && width == 512) listOf(false, true, false) else listOf(false, true)) {
                    val pixels = IntArray(width * height) { i ->
                        ((if (alpha) i % 256 else 255) shl 24) or
                            ((i % 251) shl 16) or ((i / width % 253) shl 8) or ((i * 7) % 255)
                    }
                    val cpu = NativeGegl.render(pixels, width, height, op, keys, values, false)
                    fun check(path: String, result: IntArray, useOpenCl: Boolean = eligible && active && !alpha) {
                        val label = "$path ${width}x$height $parameters alpha=$alpha"
                        assertEquals(label, useOpenCl, NativeGegl.lastRenderUsedOpenCl())
                        if (useOpenCl) {
                            val error = alphaWeightedError(cpu, result)
                            assertTrue("$label differs by $error levels", error <= 1.0)
                        } else assertArrayEquals(label, cpu, result)
                    }
                    check("array", NativeGegl.render(pixels, width, height, op, keys, values, true))

                    NativeGegl.uploadBuffer(buffer, 0, 0, width, height, pixels)
                    val rendered = NativeGegl.renderBuffer(buffer, op, keys, values, true, false)
                    val result = IntArray(pixels.size)
                    try {
                        NativeGegl.readRendered(rendered, 0, 0, width, height, result)
                        check("buffer", result)
                    } finally {
                        NativeGegl.destroyRendered(rendered)
                    }

                    NativeGegl.updateSession(session, 0, 0, width, height, pixels)
                    assertArrayEquals(intArrayOf(0, 0, width, height), NativeGegl.renderSession(session))
                    NativeGegl.readSession(session, 0, 0, width, height, result)
                    check("session", result)

                    if (width == 384 && height == 512) {
                        check("survey", NativeGegl.render(pixels, width, height, op, keys, values, true, true), active)
                    }
                }
            } finally {
                NativeGegl.destroySession(session)
                NativeGegl.destroyBuffer(buffer)
            }
        }

        val width = 3001
        val height = 2000
        val pixels = IntArray(width * height) { i ->
            0xff000000.toInt() or ((i % 251) shl 16) or ((i / width % 253) shl 8) or ((i * 7) % 255)
        }
        val op = "gegl:focus-blur"
        val keys = arrayOf("blur-type")
        val values = doubleArrayOf(1.0)
        val cpu = NativeGegl.render(pixels, width, height, op, keys, values, false)
        val normal = NativeGegl.render(pixels, width, height, op, keys, values, true)
        assertFalse("Lens Focus Blur above 6 MP must use CPU", NativeGegl.lastRenderUsedOpenCl())
        assertArrayEquals(cpu, normal)
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
        val maximum = alphaWeightedError(cpu, gpu)
        assertTrue("$op transparency differs by $maximum",maximum<=2.0)
        return maximum
    }

    private fun alphaWeightedError(cpu: IntArray, gpu: IntArray): Double {
        var maximum=0.0
        val shifts=intArrayOf(0,8,16)
        for(i in cpu.indices) {
            val a=cpu[i] ushr 24; val b=gpu[i] ushr 24
            maximum=maxOf(maximum,abs(a-b).toDouble())
            for(shift in shifts) maximum=maxOf(maximum,
                abs((cpu[i] ushr shift and 255)*a-(gpu[i] ushr shift and 255)*b)/255.0)
        }
        return maximum
    }
}
