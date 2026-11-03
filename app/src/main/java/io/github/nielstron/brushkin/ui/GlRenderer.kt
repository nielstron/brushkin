package io.github.nielstron.brushkin.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.opengl.GLES30.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import io.github.nielstron.brushkin.engine.Engine
import io.github.nielstron.brushkin.engine.Floating
import io.github.nielstron.brushkin.engine.Layer
import io.github.nielstron.brushkin.engine.LayerMode
import io.github.nielstron.brushkin.engine.Preview
import io.github.nielstron.brushkin.engine.Stack
import io.github.nielstron.brushkin.engine.TILE
import io.github.nielstron.brushkin.engine.TiledBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** What the overlay view wants drawn; built on the UI thread, read on the GL thread. */
class Frame(
    val view: Matrix,
    val filter: Boolean,
    /** Loupe: screen rect and its doc→screen matrix. */
    val loupe: Pair<RectF, Matrix>?,
)

/**
 * Renders the document with OpenGL ES 3 in **linear light**, like GIMP 2.10+: tiles are
 * linearized in the shader, layers are composited in half-float buffers (W3C blend formulas for
 * modes, masks and groups), and the result is encoded to sRGB once at the end.
 */
class GlRenderer(private val engine: Engine) : GLSurfaceView.Renderer {
    @Volatile var frame: Frame? = null

    private var w = 1; private var h = 1
    private var tileProg = 0; private var maskProg = 0; private var blendProg = 0; private var outProg = 0
    private var quad = 0
    private var halfFloat = true

    // Texture cache keyed by bitmap; re-uploaded when the bitmap's generation changes.
    private class Tex(val id: Int, var gen: Int, var used: Long)
    private val textures = HashMap<Bitmap, Tex>()
    private var frameNo = 0L

    /**
     * One texture per TiledBitmap ("atlas"), updated tile by tile with glTexSubImage2D. Sampling a
     * single texture filters seamlessly across tile borders, which per-tile textures can't.
     */
    private class Atlas(val id: Int, val cols: Int, val rows: Int, val bmps: Array<Bitmap?>, val gens: IntArray, var used: Long)
    private val atlases = java.util.IdentityHashMap<TiledBitmap, Atlas>()
    private var maxTex = 4096
    private val clearTile by lazy { Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888) }
    private val whiteTile by lazy { Bitmap.createBitmap(TILE, TILE, Bitmap.Config.ARGB_8888).apply { eraseColor(-1) } }

    // Render targets: pairs of accumulation buffers per group depth plus a layer buffer.
    private class Target(val fbo: Int, val tex: Int)
    private val pool = ArrayList<Target>()
    private var poolW = 0; private var poolH = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ext = glGetString(GL_EXTENSIONS) ?: ""
        halfFloat = "GL_EXT_color_buffer_half_float" in ext || "GL_EXT_color_buffer_float" in ext
        tileProg = program(VS_TILE, FS_TILE)
        maskProg = program(VS_TILE, FS_MASK)
        blendProg = program(VS_FULL, FS_BLEND)
        outProg = program(VS_FULL, FS_OUT)
        val b = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).also { it.position(0) }
        val ids = IntArray(1); glGenBuffers(1, ids, 0); quad = ids[0]
        glBindBuffer(GL_ARRAY_BUFFER, quad); glBufferData(GL_ARRAY_BUFFER, 32, b, GL_STATIC_DRAW)
        textures.clear(); atlases.clear(); pool.clear(); poolW = 0
        val mt = IntArray(1); glGetIntegerv(GL_MAX_TEXTURE_SIZE, mt, 0); maxTex = mt[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; glViewport(0, 0, w, h) }

    override fun onDrawFrame(gl: GL10?) {
        frameNo++
        val f = frame
        val s = engine.stack
        glDisable(GL_SCISSOR_TEST)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glClearColor(0x11 / 255f, 0x11 / 255f, 0x13 / 255f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (f == null || s.width <= 1) return
        ensurePool()
        draw(s, f.view, f.filter, null)
        f.loupe?.let { (r, m) ->
            val sc = intArrayOf(r.left.toInt(), (h - r.bottom).toInt(), r.width().toInt(), r.height().toInt())
            val k = scaleOf(m)
            draw(s, m, k < 2f, sc)
        }
        evict()
    }

    private fun draw(s: Stack, view: Matrix, filter: Boolean, scissor: IntArray?) {
        if (scissor != null) { glEnable(GL_SCISSOR_TEST); glScissor(scissor[0], scissor[1], scissor[2], scissor[3]) } else glDisable(GL_SCISSOR_TEST)
        val m = docToNdc(view)
        val result = composite(s, s.layers, 0, m, filter)
        // Output: linear premultiplied → over checkerboard → sRGB.
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, w, h)
        glDisable(GL_BLEND)
        glUseProgram(outProg)
        bindTex(0, result.tex, outProg, "uSrc")
        val inv = Matrix(); view.invert(inv)
        glUniformMatrix3fv(glGetUniformLocation(outProg, "uScreenToDoc"), 1, false, mat3(inv, flipY = true), 0)
        glUniform2f(glGetUniformLocation(outProg, "uDoc"), s.width.toFloat(), s.height.toFloat())
        glUniform2f(glGetUniformLocation(outProg, "uSize"), w.toFloat(), h.toFloat())
        glUniform1f(glGetUniformLocation(outProg, "uChecker"), 16f)
        fullQuad(outProg)
        glDisable(GL_SCISSOR_TEST)
    }

    /** Composites [list] into a pool buffer at [depth] and returns it. */
    private fun composite(s: Stack, list: List<Layer>, depth: Int, m: FloatArray, filter: Boolean): Target {
        var acc = target(depth * 3); var other = target(depth * 3 + 1)
        val layerBuf = target(depth * 3 + 2)
        clear(acc)
        val f = engine.floating
        val pv = engine.preview
        for (l in list) {
            if (!l.visible) continue
            val p = pv?.takeIf { it.layerId == l.id }
            val mask = if (!l.masked) null else if (p != null && p.mask) p.tiles else l.mask
            val simple = !l.isGroup && l.mode == LayerMode.NORMAL && mask == null
            if (simple) {
                // Fast path: premultiplied linear source-over straight into the accumulator.
                glBindFramebuffer(GL_FRAMEBUFFER, acc.fbo)
                glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                drawLayerContent(l, p, f, m, filter, l.opacity)
                continue
            }
            // Render the layer (or group) into its own buffer, apply mask, then blend.
            val content: Target = if (l.isGroup) composite(s, l.children!!, depth + 1, m, filter) else {
                clear(layerBuf)
                glBindFramebuffer(GL_FRAMEBUFFER, layerBuf.fbo)
                glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
                drawLayerContent(l, p, f, m, filter, 1f)
                layerBuf
            }
            if (mask != null) {
                glBindFramebuffer(GL_FRAMEBUFFER, content.fbo)
                glEnable(GL_BLEND); glBlendFunc(GL_ZERO, GL_SRC_ALPHA)
                glUseProgram(maskProg)
                if (p != null && p.mask && p.scale != 1f) drawTiles(maskProg, p.tiles, m, filter, p.scale)
                else drawTiles(maskProg, mask, m, filter, 1f)
            }
            glBindFramebuffer(GL_FRAMEBUFFER, other.fbo)
            glDisable(GL_BLEND)
            glUseProgram(blendProg)
            bindTex(0, acc.tex, blendProg, "uBase")
            bindTex(1, content.tex, blendProg, "uLayer")
            glUniform1i(glGetUniformLocation(blendProg, "uMode"), l.mode.ordinal)
            glUniform1f(glGetUniformLocation(blendProg, "uOpacity"), l.opacity)
            glUniform2f(glGetUniformLocation(blendProg, "uSize"), w.toFloat(), h.toFloat())
            fullQuad(blendProg)
            val t = acc; acc = other; other = t
        }
        if (depth == 0 && f != null && f.targetLayerId == null) {
            glBindFramebuffer(GL_FRAMEBUFFER, acc.fbo)
            glEnable(GL_BLEND); glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
            drawFloating(f, m, filter, 1f)
        }
        return acc
    }

    private fun drawLayerContent(l: Layer, p: Preview?, f: Floating?, m: FloatArray, filter: Boolean, opacity: Float) {
        glUseProgram(tileProg)
        glUniform1f(glGetUniformLocation(tileProg, "uOpacity"), opacity)
        val isFloatTarget = f != null && f.targetLayerId == l.id
        when {
            isFloatTarget -> f!!.remainder?.let { drawTiles(tileProg, it, m, filter, 1f) }
            p != null && !p.mask -> drawTiles(tileProg, p.tiles, m, filter, p.scale)
            else -> drawTiles(tileProg, l.pixels, m, filter, 1f)
        }
        if (isFloatTarget) drawFloating(f!!, m, filter, opacity)
    }

    private fun drawFloating(f: Floating, m: FloatArray, filter: Boolean, opacity: Float) {
        glUseProgram(tileProg)
        glUniform1f(glGetUniformLocation(tileProg, "uOpacity"), opacity)
        val fm = Matrix(f.matrix)
        val mm = multiply(m, mat3(fm, flipY = false))
        glUniformMatrix3fv(glGetUniformLocation(tileProg, "uMatrix"), 1, false, mm, 0)
        glUniform4f(glGetUniformLocation(tileProg, "uRect"), 0f, 0f, f.bitmap.width.toFloat(), f.bitmap.height.toFloat())
        bindBitmap(0, f.bitmap, tileProg, filter)
        quadDraw(tileProg)
    }

    private fun drawTiles(prog: Int, t: TiledBitmap, m: FloatArray, filter: Boolean, scale: Float) {
        glUniformMatrix3fv(glGetUniformLocation(prog, "uMatrix"), 1, false, m, 0)
        if (t.cols * TILE <= maxTex && t.rows * TILE <= maxTex) {
            val a = atlas(t, filter)
            glUniform1i(glGetUniformLocation(prog, "uTex"), 0)
            glUniform4f(glGetUniformLocation(prog, "uRect"), 0f, 0f, t.cols * TILE * scale, t.rows * TILE * scale)
            a.used = frameNo
            quadDraw(prog)
            return
        }
        val rectLoc = glGetUniformLocation(prog, "uRect")
        for (i in t.tiles.indices) {
            val b = t.tiles[i] ?: continue
            if (!visible(m, t.tileX(i) * scale, t.tileY(i) * scale, TILE * scale)) continue
            glUniform4f(rectLoc, t.tileX(i) * scale, t.tileY(i) * scale, TILE * scale, TILE * scale)
            bindBitmap(0, b, prog, filter)
            quadDraw(prog)
        }
    }

    /** Cheap cull: does the doc-space square land on screen? */
    private fun visible(m: FloatArray, x: Float, y: Float, size: Float): Boolean {
        var minX = 2f; var maxX = -2f; var minY = 2f; var maxY = -2f
        for (cx in 0..1) for (cy in 0..1) {
            val px = x + cx * size; val py = y + cy * size
            val nx = m[0] * px + m[3] * py + m[6]; val ny = m[1] * px + m[4] * py + m[7]
            minX = minOf(minX, nx); maxX = maxOf(maxX, nx); minY = minOf(minY, ny); maxY = maxOf(maxY, ny)
        }
        return maxX >= -1f && minX <= 1f && maxY >= -1f && minY <= 1f
    }

    // ------------------------------------------------------------------ GL helpers

    private fun bindBitmap(unit: Int, b: Bitmap, prog: Int, filter: Boolean) {
        val gen = b.generationId
        var t = textures[b]
        glActiveTexture(GL_TEXTURE0 + unit)
        if (t == null) {
            val ids = IntArray(1); glGenTextures(1, ids, 0)
            t = Tex(ids[0], gen - 1, 0); textures[b] = t
        }
        glBindTexture(GL_TEXTURE_2D, t.id)
        if (t.gen != gen) {
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, b, 0)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            t.gen = gen
        }
        val mode = if (filter) GL_LINEAR else GL_NEAREST
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mode)
        t.used = frameNo
        glUniform1i(glGetUniformLocation(prog, "uTex"), unit)
    }

    private fun atlas(t: TiledBitmap, filter: Boolean): Atlas {
        var a = atlases[t]
        glActiveTexture(GL_TEXTURE0)
        if (a == null) {
            val ids = IntArray(1); glGenTextures(1, ids, 0)
            glBindTexture(GL_TEXTURE_2D, ids[0])
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, t.cols * TILE, t.rows * TILE, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            a = Atlas(ids[0], t.cols, t.rows, arrayOfNulls(t.tiles.size), IntArray(t.tiles.size) { Int.MIN_VALUE }, frameNo)
            atlases[t] = a
        } else glBindTexture(GL_TEXTURE_2D, a.id)
        // Upload only tiles whose bitmap or contents changed since last frame.
        for (i in t.tiles.indices) {
            val b = t.tiles[i]
            val gen = b?.generationId ?: 0
            if (a.bmps[i] === b && a.gens[i] == gen && (b != null || a.gens[i] == 0)) continue
            GLUtils.texSubImage2D(GL_TEXTURE_2D, 0, t.tileX(i), t.tileY(i), b ?: if (t.fill != 0) whiteTile else clearTile)
            a.bmps[i] = b; a.gens[i] = gen
        }
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, if (filter) GL_LINEAR else GL_NEAREST)
        return a
    }

    /** Drop textures not used in the last few seconds of frames (old tile versions, closed docs). */
    private fun evict() {
        val ai = atlases.entries.iterator()
        while (ai.hasNext()) {
            val e = ai.next()
            if (frameNo - e.value.used > 300) { glDeleteTextures(1, intArrayOf(e.value.id), 0); ai.remove() }
        }
        if (textures.size < 256) return
        val it = textures.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (frameNo - e.value.used > 120 || e.key.isRecycled) {
                glDeleteTextures(1, intArrayOf(e.value.id), 0); it.remove()
            }
        }
    }

    private fun bindTex(unit: Int, tex: Int, prog: Int, name: String) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, tex)
        glUniform1i(glGetUniformLocation(prog, name), unit)
    }

    private fun target(i: Int): Target {
        while (pool.size <= i) {
            val tex = IntArray(1); glGenTextures(1, tex, 0)
            glBindTexture(GL_TEXTURE_2D, tex[0])
            if (halfFloat) glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, w, h, 0, GL_RGBA, GL_HALF_FLOAT, null)
            else glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            val fbo = IntArray(1); glGenFramebuffers(1, fbo, 0)
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[0])
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[0], 0)
            pool.add(Target(fbo[0], tex[0]))
        }
        return pool[i]
    }

    private fun ensurePool() {
        if (poolW == w && poolH == h) return
        for (t in pool) { glDeleteFramebuffers(1, intArrayOf(t.fbo), 0); glDeleteTextures(1, intArrayOf(t.tex), 0) }
        pool.clear(); poolW = w; poolH = h
    }

    private fun clear(t: Target) {
        glBindFramebuffer(GL_FRAMEBUFFER, t.fbo)
        glViewport(0, 0, w, h)
        glClearColor(0f, 0f, 0f, 0f); glClear(GL_COLOR_BUFFER_BIT)
    }

    private fun quadDraw(prog: Int) {
        val loc = glGetAttribLocation(prog, "aPos")
        glBindBuffer(GL_ARRAY_BUFFER, quad)
        glEnableVertexAttribArray(loc)
        glVertexAttribPointer(loc, 2, GL_FLOAT, false, 0, 0)
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun fullQuad(prog: Int) = quadDraw(prog)

    /** doc → NDC for the current viewport (screen y down → GL y up). */
    private fun docToNdc(view: Matrix): FloatArray {
        val toNdc = Matrix().apply { setScale(2f / w, -2f / h); postTranslate(-1f, 1f) }
        val mm = Matrix(view); mm.postConcat(toNdc)
        return mat3(mm, flipY = false)
    }

    /** Android Matrix → column-major GLSL mat3. [flipY]: input y is GL (bottom-up) screen y. */
    private fun mat3(m: Matrix, flipY: Boolean): FloatArray {
        val mm = Matrix(m)
        if (flipY) mm.preConcat(Matrix().apply { setScale(1f, -1f); postTranslate(0f, h.toFloat()) })
        val v = FloatArray(9); mm.getValues(v)
        return floatArrayOf(v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8])
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(9)
        for (c in 0..2) for (rr in 0..2) r[c * 3 + rr] = a[0 * 3 + rr] * b[c * 3 + 0] + a[1 * 3 + rr] * b[c * 3 + 1] + a[2 * 3 + rr] * b[c * 3 + 2]
        return r
    }

    private fun scaleOf(m: Matrix): Float { val v = FloatArray(9); m.getValues(v); return kotlin.math.hypot(v[0], v[3]) }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = glCreateShader(type); glShaderSource(s, src); glCompileShader(s)
            val ok = IntArray(1); glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "Shader: " + glGetShaderInfoLog(s) }
            return s
        }
        val p = glCreateProgram()
        glAttachShader(p, shader(GL_VERTEX_SHADER, vs)); glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs))
        glLinkProgram(p)
        val ok = IntArray(1); glGetProgramiv(p, GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program: " + glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val VS_TILE = """#version 300 es
in vec2 aPos;
uniform mat3 uMatrix;   // doc -> NDC
uniform vec4 uRect;     // doc x, y, w, h of this quad
out vec2 vUv;
void main() {
    vec2 doc = uRect.xy + aPos * uRect.zw;
    vec3 p = uMatrix * vec3(doc, 1.0);
    vUv = aPos;
    gl_Position = vec4(p.xy, 0.0, 1.0);
}"""

        private const val SRGB = """
vec3 toLinear(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { c = max(c, 0.0); return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
"""

        /** Tiles are premultiplied sRGB; output premultiplied linear. */
        private const val FS_TILE = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uOpacity;
out vec4 o;
""" + SRGB + """
void main() {
    vec4 c = texture(uTex, vUv);
    vec3 lin = c.a > 0.0 ? toLinear(c.rgb / c.a) * c.a : vec3(0.0);
    o = vec4(lin, c.a) * uOpacity;
}"""

        /** Mask tiles: gray value used directly as coverage (GL blend multiplies the destination). */
        private const val FS_MASK = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 o;
void main() {
    vec3 c = texture(uTex, vUv).rgb;
    o = vec4(0.0, 0.0, 0.0, dot(c, vec3(0.299, 0.587, 0.114)));
}"""

        private const val VS_FULL = """#version 300 es
in vec2 aPos;
void main() { gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }"""

        /** W3C compositing with blend modes; indices follow LayerMode's order. */
        private const val FS_BLEND = """#version 300 es
precision highp float;
uniform sampler2D uBase;
uniform sampler2D uLayer;
uniform int uMode;
uniform float uOpacity;
uniform vec2 uSize;
out vec4 o;
float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }   // W3C, as in Skia
vec3 clipColor(vec3 c) {
    float l = lum(c); float n = min(min(c.r, c.g), c.b); float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / max(l - n, 1e-6);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / max(x - l, 1e-6);
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) {
    float mx = max(max(c.r, c.g), c.b); float mn = min(min(c.r, c.g), c.b);
    return mx > mn ? (c - mn) * s / (mx - mn) : vec3(0.0);
}
float softLight(float b, float s) {
    float d = b <= 0.25 ? ((16.0 * b - 12.0) * b + 4.0) * b : sqrt(b);
    return s <= 0.5 ? b - (1.0 - 2.0 * s) * b * (1.0 - b) : b + (2.0 * s - 1.0) * (d - b);
}
float dodge(float b, float s) { return b == 0.0 ? 0.0 : (s >= 1.0 ? 1.0 : min(1.0, b / (1.0 - s))); }
float burn(float b, float s) { return b >= 1.0 ? 1.0 : (s <= 0.0 ? 0.0 : 1.0 - min(1.0, (1.0 - b) / s)); }
vec3 hardLight(vec3 b, vec3 s) { return mix(b * 2.0 * s, 1.0 - (1.0 - b) * (1.0 - (2.0 * s - 1.0)), step(0.5, s)); }
vec3 blendFn(vec3 b, vec3 s) {
    if (uMode == 1) return b * s;                                   // multiply
    if (uMode == 2) return b + s - b * s;                           // screen
    if (uMode == 3) return hardLight(s, b);                         // overlay
    if (uMode == 4) return min(b, s);                               // darken
    if (uMode == 5) return max(b, s);                               // lighten
    if (uMode == 6) return vec3(dodge(b.r, s.r), dodge(b.g, s.g), dodge(b.b, s.b));
    if (uMode == 7) return vec3(burn(b.r, s.r), burn(b.g, s.g), burn(b.b, s.b));
    if (uMode == 8) return hardLight(b, s);
    if (uMode == 9) return vec3(softLight(b.r, s.r), softLight(b.g, s.g), softLight(b.b, s.b));
    if (uMode == 10) return abs(b - s);                             // difference
    if (uMode == 11) return b + s - 2.0 * b * s;                    // exclusion
    if (uMode == 12) return setLum(setSat(s, sat(b)), lum(b));      // hue
    if (uMode == 13) return setLum(setSat(b, sat(s)), lum(b));      // saturation
    if (uMode == 14) return setLum(s, lum(b));                      // color
    if (uMode == 15) return setLum(b, lum(s));                      // luminosity
    if (uMode == 16) return min(b + s, 1.0);                        // addition
    return s;                                                       // normal
}
void main() {
    vec2 uv = gl_FragCoord.xy / uSize;
    vec4 d = texture(uBase, uv);
    vec4 s = texture(uLayer, uv) * uOpacity;
    vec3 cb = d.a > 0.0 ? d.rgb / d.a : vec3(0.0);
    vec3 cs = s.a > 0.0 ? s.rgb / s.a : vec3(0.0);
    if (uMode == 16) { o = min(s + d, 1.0); return; }               // addition = Skia's PLUS
    vec3 mixed = (1.0 - d.a) * cs + d.a * clamp(blendFn(cb, cs), 0.0, 1.0);
    o = vec4(s.a * mixed + (1.0 - s.a) * d.rgb, s.a + d.a * (1.0 - s.a));
}"""

        /** Composite over the transparency checkerboard inside the document, encode to sRGB. */
        private const val FS_OUT = """#version 300 es
precision highp float;
uniform sampler2D uSrc;
uniform mat3 uScreenToDoc;
uniform vec2 uDoc;
uniform vec2 uSize;
uniform float uChecker;
out vec4 o;
""" + SRGB + """
void main() {
    vec3 doc = uScreenToDoc * vec3(gl_FragCoord.xy, 1.0);
    vec3 ground = vec3(0x11, 0x11, 0x13) / 255.0;
    if (doc.x < 0.0 || doc.y < 0.0 || doc.x > uDoc.x || doc.y > uDoc.y) { o = vec4(ground, 1.0); return; }
    vec2 cell = floor(gl_FragCoord.xy / uChecker);
    vec3 checker = mod(cell.x + cell.y, 2.0) < 1.0 ? vec3(0x3A, 0x3A, 0x40) / 255.0 : vec3(0x2E, 0x2E, 0x34) / 255.0;
    vec4 c = texture(uSrc, gl_FragCoord.xy / uSize);
    vec3 lin = c.rgb + toLinear(checker) * (1.0 - c.a);
    o = vec4(toSrgb(lin), 1.0);
}"""
    }
}
