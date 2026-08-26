package com.chuks.app

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// The GPU op-chain executor (Android), mirroring iOS's Core Image executor. Runs a Chuks
// effects.chain (JSON op list) through an offscreen GLES2 FBO ping-pong pipeline, one
// fragment shader per generic op. Universal (GLES2 runs on every Android version, not
// gated like RenderEffect). See effects.chuks + docs/mobile-compute-and-effects.md. abi 1.
object ChuksEffects {

    fun run(src: Bitmap, opsJson: String): Bitmap {
        if (opsJson.isEmpty()) return src
        val ops: JSONArray = try { JSONArray(opsJson) } catch (e: Exception) { return src }
        if (ops.length() == 0) return src
        val w = src.width; val h = src.height
        val egl = Egl()
        try {
            egl.init()
            val prog = Programs()
            // upload source, set up two ping-pong render targets
            var texA = uploadTexture(src)
            var texB = emptyTexture(w, h)
            val fboA = makeFbo(texA); val fboB = makeFbo(texB)
            var curTex = texA; var curFbo = fboA; var dstTex = texB; var dstFbo = fboB
            var pendingSource = -1   // a generated gradient/noise texture for the next blend

            val quad = quadBuffer()
            GLES20.glViewport(0, 0, w, h)

            for (i in 0 until ops.length()) {
                val op = ops.optJSONObject(i) ?: continue
                when (op.optString("op")) {
                    "colorMatrix" -> { colorMatrix(prog, op, curTex, dstFbo, quad); val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f }
                    "threshold"   -> { threshold(prog, op, curTex, dstFbo, quad); val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f }
                    "curves"      -> { curves(prog, op, curTex, dstFbo, quad); val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f }
                    "gradientMap" -> { gradientMap(prog, op, curTex, dstFbo, quad); val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f }
                    "convolve"    -> { convolve(prog, op, curTex, dstFbo, quad, w, h); val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f }
                    "gradient"    -> { pendingSource = genGradient(prog, op, w, h, quad) }
                    "noise"       -> { pendingSource = genNoise(prog, op, w, h, quad) }
                    "blend"       -> {
                        if (pendingSource >= 0) {
                            blend(prog, op, curTex, pendingSource, dstFbo, quad)
                            GLES20.glDeleteTextures(1, intArrayOf(pendingSource), 0); pendingSource = -1
                            val t = curTex; curTex = dstTex; dstTex = t; val f = curFbo; curFbo = dstFbo; dstFbo = f
                        }
                    }
                    else -> {}
                }
            }
            val out = readback(curFbo, w, h)
            return out
        } catch (e: Exception) {
            return src
        } finally {
            egl.destroy()
        }
    }

    // ── ops (each renders curTex -> dstFbo through a fragment shader) ─────────────
    private fun colorMatrix(p: Programs, op: JSONObject, tex: Int, fbo: Int, quad: FloatBuffer) {
        val m = floats(op.optJSONArray("m"), 20)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.colorMatrix)
        bindQuad(p.colorMatrix, quad); bindTex(p.colorMatrix, tex, 0)
        // column-major mat4 for rgba->rgba + a bias vec4
        val mat = floatArrayOf(
            m[0], m[5], m[10], m[15],
            m[1], m[6], m[11], m[16],
            m[2], m[7], m[12], m[17],
            m[3], m[8], m[13], m[18])
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p.colorMatrix, "uMat"), 1, false, mat, 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(p.colorMatrix, "uBias"), m[4], m[9], m[14], m[19])
        draw()
    }
    private fun threshold(p: Programs, op: JSONObject, tex: Int, fbo: Int, quad: FloatBuffer) {
        val levels = op.optDouble("levels", 6.0).toFloat().coerceAtLeast(2f)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.threshold); bindQuad(p.threshold, quad); bindTex(p.threshold, tex, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p.threshold, "uLevels"), levels)
        draw()
    }
    private fun curves(p: Programs, op: JSONObject, tex: Int, fbo: Int, quad: FloatBuffer) {
        val lut = curveLutTexture(op)   // 256x1 RGBA LUT
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.curves); bindQuad(p.curves, quad); bindTex(p.curves, tex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lut)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p.curves, "uLut"), 1)
        draw(); GLES20.glDeleteTextures(1, intArrayOf(lut), 0)
    }
    private fun gradientMap(p: Programs, op: JSONObject, tex: Int, fbo: Int, quad: FloatBuffer) {
        val lut = gradientLutTexture(op.optJSONArray("stops"))
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.gradientMap); bindQuad(p.gradientMap, quad); bindTex(p.gradientMap, tex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lut)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p.gradientMap, "uLut"), 1)
        draw(); GLES20.glDeleteTextures(1, intArrayOf(lut), 0)
    }
    private fun convolve(p: Programs, op: JSONObject, tex: Int, fbo: Int, quad: FloatBuffer, w: Int, h: Int) {
        val k = floats(op.optJSONArray("k"), 9)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.convolve); bindQuad(p.convolve, quad); bindTex(p.convolve, tex, 0)
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(p.convolve, "uK"), 9, k, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p.convolve, "uTexel"), 1f / w, 1f / h)
        draw()
    }
    private fun genGradient(p: Programs, op: JSONObject, w: Int, h: Int, quad: FloatBuffer): Int {
        val tex = emptyTexture(w, h); val fbo = makeFbo(tex)
        val stops = op.optJSONArray("stops"); val geo = intArr(op.optJSONArray("geo"), intArrayOf(0,0,100,100))
        val c0 = hex(stopColor(stops, 0)); val c1 = hex(stopColor(stops, 1))
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.gradient); bindQuad(p.gradient, quad)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p.gradient, "uRadial"), if (op.optString("kind") == "radial") 1 else 0)
        GLES20.glUniform4f(GLES20.glGetUniformLocation(p.gradient, "uGeo"), geo[0]/100f, geo[1]/100f, geo[2]/100f, geo[3]/100f)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p.gradient, "uC0"), c0[0], c0[1], c0[2])
        GLES20.glUniform3f(GLES20.glGetUniformLocation(p.gradient, "uC1"), c1[0], c1[1], c1[2])
        draw(); GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); return tex
    }
    private fun genNoise(p: Programs, op: JSONObject, w: Int, h: Int, quad: FloatBuffer): Int {
        val tex = emptyTexture(w, h); val fbo = makeFbo(tex)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.noise); bindQuad(p.noise, quad)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p.noise, "uSize"), w.toFloat(), h.toFloat())
        draw(); GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0); return tex
    }
    private fun blend(p: Programs, op: JSONObject, base: Int, over: Int, fbo: Int, quad: FloatBuffer) {
        val mode = op.optString("mode", "normal"); val opacity = op.optDouble("opacity", 100.0).toFloat() / 100f
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glUseProgram(p.blend); bindQuad(p.blend, quad); bindTex(p.blend, base, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, over)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p.blend, "uOver"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p.blend, "uMode"), blendModeId(mode))
        GLES20.glUniform1f(GLES20.glGetUniformLocation(p.blend, "uOpacity"), opacity)
        draw()
    }

    private fun draw() { GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4) }
    private fun blendModeId(m: String) = when (m) { "multiply" -> 1; "screen" -> 2; "overlay" -> 3; "add" -> 4; "softlight" -> 5; "difference" -> 6; else -> 0 }

    // ── GL helpers ───────────────────────────────────────────────────────────────
    private fun bindTex(prog: Int, tex: Int, unit: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uTex"), unit)
    }
    private fun bindQuad(prog: Int, quad: FloatBuffer) {
        val pos = GLES20.glGetAttribLocation(prog, "aPos"); val uv = GLES20.glGetAttribLocation(prog, "aUv")
        quad.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(pos)
        quad.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(uv)
    }
    private fun quadBuffer(): FloatBuffer {
        // Normal texcoords + no readback flip: the two flips (GLUtils upload origin and
        // glReadPixels bottom-up read) cancel, keeping the image upright.
        val v = floatArrayOf(-1f,-1f, 0f,0f,  1f,-1f, 1f,0f,  -1f,1f, 0f,1f,  1f,1f, 1f,1f)
        val b = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer(); b.put(v); b.position(0); return b
    }
    private fun uploadTexture(bmp: Bitmap): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        texParams(); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0); return t[0]
    }
    private fun emptyTexture(w: Int, h: Int): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
        texParams(); GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null); return t[0]
    }
    private fun texParams() {
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }
    private fun makeFbo(tex: Int): Int {
        val f = IntArray(1); GLES20.glGenFramebuffers(1, f, 0); GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0); return f[0]
    }
    private fun readback(fbo: Int, w: Int, h: Int): Bitmap {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); buf.position(0); out.copyPixelsFromBuffer(buf)
        return out   // quad texcoords already compensate for GL's bottom-up origin
    }

    // ── data helpers ─────────────────────────────────────────────────────────────
    private fun floats(a: JSONArray?, n: Int): FloatArray { val r = FloatArray(n); if (a != null) for (i in 0 until minOf(n, a.length())) r[i] = a.optDouble(i).toFloat(); return r }
    private fun intArr(a: JSONArray?, def: IntArray): IntArray { if (a == null || a.length() < def.size) return def; return IntArray(def.size) { a.optInt(it) } }
    private fun stopColor(stops: JSONArray?, idx: Int): String {
        if (stops == null || stops.length() == 0) return if (idx == 0) "000000" else "FFFFFF"
        val i = if (idx == 0) 0 else stops.length() - 1
        val s = stops.optJSONArray(i) ?: return "888888"
        return s.optString(s.length() - 1, "888888")
    }
    private fun hex(h: String): FloatArray {
        val s = h.removePrefix("#"); return try { val n = s.toLong(16); floatArrayOf(((n shr 16) and 0xff)/255f, ((n shr 8) and 0xff)/255f, (n and 0xff)/255f) } catch (e: Exception) { floatArrayOf(0.5f,0.5f,0.5f) }
    }
    private fun curveLutTexture(op: JSONObject): Int {
        val pts = op.optJSONArray("pts"); val lut = ByteArray(256 * 4)
        for (x in 0 until 256) { val y = interpCurve(pts, x); for (c in 0 until 3) lut[x*4+c] = y.toByte(); lut[x*4+3] = 255.toByte() }
        return lut1d(lut)
    }
    private fun gradientLutTexture(stops: JSONArray?): Int {
        val c0 = hex(stopColor(stops, 0)); val c1 = hex(stopColor(stops, 1)); val lut = ByteArray(256 * 4)
        for (x in 0 until 256) { val t = x/255f; lut[x*4] = ((c0[0]+(c1[0]-c0[0])*t)*255).toInt().toByte(); lut[x*4+1] = ((c0[1]+(c1[1]-c0[1])*t)*255).toInt().toByte(); lut[x*4+2] = ((c0[2]+(c1[2]-c0[2])*t)*255).toInt().toByte(); lut[x*4+3] = 255.toByte() }
        return lut1d(lut)
    }
    private fun lut1d(data: ByteArray): Int {
        val t = IntArray(1); GLES20.glGenTextures(1, t, 0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]); texParams()
        val buf = ByteBuffer.allocateDirect(data.size).order(ByteOrder.nativeOrder()); buf.put(data); buf.position(0)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf); return t[0]
    }
    private fun interpCurve(pts: JSONArray?, x: Int): Int {
        if (pts == null || pts.length() < 2) return x
        var y = pts.optJSONArray(0)?.optInt(1) ?: x
        for (j in 1 until pts.length()) {
            val p0 = pts.optJSONArray(j-1) ?: continue; val p1 = pts.optJSONArray(j) ?: continue
            val x0 = p0.optInt(0); val y0 = p0.optInt(1); val x1 = p1.optInt(0); val y1 = p1.optInt(1)
            if (x <= x1) { val t = if (x1 > x0) (x - x0).toFloat()/(x1 - x0) else 0f; y = (y0 + (y1 - y0)*t.coerceIn(0f,1f)).toInt(); break }
            y = y1
        }
        return y.coerceIn(0, 255)
    }

    // ── EGL offscreen context ────────────────────────────────────────────────────
    private class Egl {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var surface: EGLSurface = EGL14.EGL_NO_SURFACE
        fun init() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)
            val cfg = arrayOfNulls<EGLConfig>(1)
            EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE), 0, cfg, 0, 1, IntArray(1), 0)
            context = EGL14.eglCreateContext(display, cfg[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, cfg[0], intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            EGL14.eglMakeCurrent(display, surface, surface, context)
        }
        fun destroy() {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
    }

    // ── shader programs ──────────────────────────────────────────────────────────
    private class Programs {
        val colorMatrix = Shaders.prog(Shaders.VS, Shaders.FS_COLOR_MATRIX)
        val threshold = Shaders.prog(Shaders.VS, Shaders.FS_THRESHOLD)
        val curves = Shaders.prog(Shaders.VS, Shaders.FS_CURVES)
        val gradientMap = Shaders.prog(Shaders.VS, Shaders.FS_GRADIENT_MAP)
        val convolve = Shaders.prog(Shaders.VS, Shaders.FS_CONVOLVE)
        val gradient = Shaders.prog(Shaders.VS, Shaders.FS_GRADIENT)
        val noise = Shaders.prog(Shaders.VS, Shaders.FS_NOISE)
        val blend = Shaders.prog(Shaders.VS, Shaders.FS_BLEND)
    }
}

private object Shaders {
        fun prog(vs: String, fs: String): Int {
            val v = shader(GLES20.GL_VERTEX_SHADER, vs); val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
            val p = GLES20.glCreateProgram(); GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p); return p
        }
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s); return s
        }
        const val VS = "attribute vec2 aPos; attribute vec2 aUv; varying vec2 vUv; void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0); }"
        const val FS_COLOR_MATRIX = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform mat4 uMat; uniform vec4 uBias; void main(){ vec4 c=texture2D(uTex,vUv); gl_FragColor=clamp(uMat*c+uBias,0.0,1.0); }"
        const val FS_THRESHOLD = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform float uLevels; void main(){ vec4 c=texture2D(uTex,vUv); gl_FragColor=vec4(floor(c.rgb*uLevels+0.5)/uLevels,c.a); }"
        const val FS_CURVES = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform sampler2D uLut; void main(){ vec4 c=texture2D(uTex,vUv); float r=texture2D(uLut,vec2(c.r,0.5)).r; float g=texture2D(uLut,vec2(c.g,0.5)).g; float b=texture2D(uLut,vec2(c.b,0.5)).b; gl_FragColor=vec4(r,g,b,c.a); }"
        const val FS_GRADIENT_MAP = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform sampler2D uLut; void main(){ vec4 c=texture2D(uTex,vUv); float l=dot(c.rgb,vec3(0.299,0.587,0.114)); gl_FragColor=vec4(texture2D(uLut,vec2(l,0.5)).rgb,c.a); }"
        const val FS_CONVOLVE = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform float uK[9]; uniform vec2 uTexel; void main(){ vec3 s=vec3(0.0); int idx=0; for(int y=-1;y<=1;y++){ for(int x=-1;x<=1;x++){ s+=texture2D(uTex,vUv+vec2(float(x),float(y))*uTexel).rgb*uK[idx]; idx++; } } gl_FragColor=vec4(clamp(s,0.0,1.0),1.0); }"
        const val FS_GRADIENT = "precision mediump float; varying vec2 vUv; uniform int uRadial; uniform vec4 uGeo; uniform vec3 uC0; uniform vec3 uC1; void main(){ float t; if(uRadial==1){ float r=distance(uGeo.zw,uGeo.xy); t=clamp(distance(vUv,uGeo.xy)/max(r,0.001),0.0,1.0);} else { vec2 d=uGeo.zw-uGeo.xy; t=clamp(dot(vUv-uGeo.xy,d)/max(dot(d,d),0.001),0.0,1.0);} gl_FragColor=vec4(mix(uC0,uC1,t),1.0); }"
        const val FS_NOISE = "precision mediump float; varying vec2 vUv; uniform vec2 uSize; float h(vec2 p){ return fract(sin(dot(p,vec2(12.9898,78.233)))*43758.5453);} void main(){ float n=h(vUv*uSize); gl_FragColor=vec4(vec3(n),1.0); }"
        const val FS_BLEND = "precision mediump float; varying vec2 vUv; uniform sampler2D uTex; uniform sampler2D uOver; uniform int uMode; uniform float uOpacity; vec3 bl(vec3 b, vec3 o, int m){ if(m==1) return b*o; if(m==2) return 1.0-(1.0-b)*(1.0-o); if(m==3) return mix(2.0*b*o, 1.0-2.0*(1.0-b)*(1.0-o), step(0.5,b)); if(m==4) return min(b+o,1.0); if(m==5) return mix(2.0*b*o+b*b*(1.0-2.0*o), sqrt(b)*(2.0*o-1.0)+2.0*b*(1.0-o), step(0.5,o)); if(m==6) return abs(b-o); return o; } void main(){ vec4 b=texture2D(uTex,vUv); vec4 o=texture2D(uOver,vUv); vec3 r=bl(b.rgb,o.rgb,uMode); gl_FragColor=vec4(mix(b.rgb, r, uOpacity*o.a), b.a); }"
    }
