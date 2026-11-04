package io.github.foadsf.microscope.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import io.github.foadsf.microscope.imaging.Affine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** How the live view is drawn. Everything the GL thread needs, swapped in atomically. */
data class RenderParams(
    /** Sensor orientation: tells which way round the preview buffer's width and height are. */
    val rotation: Int = 90,
    /** Magnification on top of the ISP crop. */
    val glZoom: Float = 1f,
    /** View centre relative to the crop centre, in crop-normalised units (non-zero only for centre-only crops). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Camera preview buffer size, as delivered (sensor orientation). */
    val bufferWidth: Int = 1440,
    val bufferHeight: Int = 1080,
    val filter: Filter = Filter.COLOUR,
    val peaking: Boolean = true,
    /** Stripe pixels where any colour channel is clipped, so exposure can be set by eye. */
    val zebra: Boolean = false,
    val sharpen: Float = 0.6f,
    val contrast: Float = 1.0f,
)

enum class Filter(val label: String) { COLOUR("Colour"), MONO("Mono"), EDGES("Edges"), RELIEF("Relief") }

/**
 * Draws the camera stream from a `GL_TEXTURE_EXTERNAL_OES` texture with one fragment pass: unsharp
 * mask, contrast, Sobel focus peaking, the inspection filters and a clipping zebra. The GPU only does what must run
 * per displayed frame; captures are processed on the CPU at full resolution.
 */
class PreviewRenderer(
    private val onTexture: (SurfaceTexture) -> Unit,
    private val requestRender: () -> Unit,
) : GLSurfaceView.Renderer {
    private val params = AtomicReference(RenderParams())
    private val texMatrix = FloatArray(16)
    private var texture: SurfaceTexture? = null
    private var textureId = 0
    private var program = 0
    private var viewWidth = 1
    private var viewHeight = 1
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder())
        .asFloatBuffer().apply { put(QUAD).position(0) }

    private var uRow0 = 0
    private var uRow1 = 0
    private var uStepX = 0
    private var uStepY = 0
    private var uFilter = 0
    private var uPeak = 0
    private var uZebra = 0
    private var uSharpen = 0
    private var uContrast = 0

    fun update(p: RenderParams) {
        if (params.getAndSet(p) != p) requestRender()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        textureId = ids[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        program = link(VERTEX, FRAGMENT)
        uRow0 = GLES30.glGetUniformLocation(program, "uRow0")
        uRow1 = GLES30.glGetUniformLocation(program, "uRow1")
        uStepX = GLES30.glGetUniformLocation(program, "uStepX")
        uStepY = GLES30.glGetUniformLocation(program, "uStepY")
        uFilter = GLES30.glGetUniformLocation(program, "uFilter")
        uPeak = GLES30.glGetUniformLocation(program, "uPeak")
        uZebra = GLES30.glGetUniformLocation(program, "uZebra")
        uSharpen = GLES30.glGetUniformLocation(program, "uSharpen")
        uContrast = GLES30.glGetUniformLocation(program, "uContrast")
        android.opengl.Matrix.setIdentityM(texMatrix, 0)

        texture?.release()
        val st = SurfaceTexture(textureId)
        st.setOnFrameAvailableListener { requestRender() }
        texture = st
        onTexture(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = texture ?: return
        try {
            st.updateTexImage()
            st.getTransformMatrix(texMatrix)
        } catch (e: RuntimeException) {
            Log.w(TAG, "updateTexImage", e) // the producer is being torn down
        }
        val p = params.get()
        val f = viewToTexture(p, texMatrix)
        val (upW, upH) = if (p.rotation % 180 == 0) p.bufferWidth to p.bufferHeight else p.bufferHeight to p.bufferWidth
        // One upright buffer pixel, expressed in texture coordinates, for the neighbourhood taps.
        val sx = p.glZoom / upW
        val sy = p.glZoom / upH

        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)
        GLES30.glUniform3f(uRow0, f.a.toFloat(), f.b.toFloat(), f.c.toFloat())
        GLES30.glUniform3f(uRow1, f.d.toFloat(), f.e.toFloat(), f.f.toFloat())
        GLES30.glUniform2f(uStepX, (f.a * sx).toFloat(), (f.d * sx).toFloat())
        GLES30.glUniform2f(uStepY, (f.b * sy).toFloat(), (f.e * sy).toFloat())
        GLES30.glUniform1i(uFilter, p.filter.ordinal)
        GLES30.glUniform1i(uPeak, if (p.peaking) 1 else 0)
        GLES30.glUniform1i(uZebra, if (p.zebra) 1 else 0)
        GLES30.glUniform1f(uSharpen, p.sharpen)
        GLES30.glUniform1f(uContrast, p.contrast)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

        quad.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(0)
        quad.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, quad)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    fun release() {
        texture?.release()
        texture = null
    }

    private fun link(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, v)
        GLES30.glAttachShader(prog, f)
        GLES30.glBindAttribLocation(prog, 0, "aPos")
        GLES30.glBindAttribLocation(prog, 1, "aUv")
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "link: " + GLES30.glGetProgramInfoLog(prog) }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "compile: " + GLES30.glGetShaderInfoLog(s) }
        return s
    }

    companion object {
        private const val TAG = "PreviewRenderer"

        // x, y (clip space), u, v (view space: origin top-left, v down).
        private val QUAD = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f,
        )

        /**
         * View uv → SurfaceTexture texture coordinates: GL zoom and pan within the crop, flip to GL's
         * bottom-up st, then the producer's transform. For a camera stream that transform already
         * contains the sensor rotation (the camera service sets it on preview streams), so the
         * renderer must not rotate again; only the YUV capture path, which has no transform,
         * rotates by the sensor orientation itself.
         */
        fun viewToTexture(p: RenderParams, m: FloatArray): Affine {
            val z = p.glZoom.toDouble()
            val zoom = Affine(a = 1 / z, c = 0.5 - 0.5 / z + p.offsetX, e = 1 / z, f = 0.5 - 0.5 / z + p.offsetY)
            val flip = Affine(e = -1.0, f = 1.0)
            val producer = Affine(
                m[0].toDouble(), m[4].toDouble(), m[12].toDouble(),
                m[1].toDouble(), m[5].toDouble(), m[13].toDouble(),
            )
            return producer.compose(flip).compose(zoom)
        }

        /** Upright normalised (s right, t down) → buffer normalised (u right, v down). */
        fun uprightToBuffer(rotation: Int): Affine = when (((rotation % 360) + 360) % 360) {
            90 -> Affine(a = 0.0, b = 1.0, c = 0.0, d = -1.0, e = 0.0, f = 1.0)
            180 -> Affine(a = -1.0, c = 1.0, e = -1.0, f = 1.0)
            270 -> Affine(a = 0.0, b = -1.0, c = 1.0, d = 1.0, e = 0.0, f = 0.0)
            else -> Affine.IDENTITY
        }

        private const val VERTEX = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform vec3 uRow0;
uniform vec3 uRow1;
out vec2 vTex;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    vec3 uv = vec3(aUv, 1.0);
    vTex = vec2(dot(uRow0, uv), dot(uRow1, uv));
}
"""

        private const val FRAGMENT = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform vec2 uStepX;
uniform vec2 uStepY;
uniform int uFilter;
uniform int uPeak;
uniform int uZebra;
uniform float uSharpen;
uniform float uContrast;
in vec2 vTex;
out vec4 outColor;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

void main() {
    vec3 c  = texture(uTex, vTex).rgb;
    vec3 n  = texture(uTex, vTex - uStepY).rgb;
    vec3 s  = texture(uTex, vTex + uStepY).rgb;
    vec3 w  = texture(uTex, vTex - uStepX).rgb;
    vec3 e  = texture(uTex, vTex + uStepX).rgb;
    vec3 nw = texture(uTex, vTex - uStepX - uStepY).rgb;
    vec3 ne = texture(uTex, vTex + uStepX - uStepY).rgb;
    vec3 sw = texture(uTex, vTex - uStepX + uStepY).rgb;
    vec3 se = texture(uTex, vTex + uStepX + uStepY).rgb;

    // Unsharp mask against a 3x3 binomial blur, then contrast about mid-grey.
    vec3 blur = (4.0 * c + 2.0 * (n + s + w + e) + (nw + ne + sw + se)) / 16.0;
    vec3 col = clamp(c + uSharpen * (c - blur), 0.0, 1.0);
    col = clamp((col - 0.5) * uContrast + 0.5, 0.0, 1.0);

    // Sobel gradient of luma.
    float gx = (luma(ne) + 2.0 * luma(e) + luma(se)) - (luma(nw) + 2.0 * luma(w) + luma(sw));
    float gy = (luma(sw) + 2.0 * luma(s) + luma(se)) - (luma(nw) + 2.0 * luma(n) + luma(ne));
    float g = length(vec2(gx, gy));

    if (uFilter == 1) {
        col = vec3(luma(col));
    } else if (uFilter == 2) {
        // Edge map on black: a dark-field-like rendering of relief and scratches.
        col = vec3(smoothstep(0.02, 0.6, g)) * vec3(0.85, 0.97, 1.0);
    } else if (uFilter == 3) {
        // Oblique light from the top left: the luma gradient along that direction on mid-grey,
        // which reads as surface relief (like DIC or a raking light) on polished metal.
        col = vec3(clamp(0.5 - 0.6 * (gx + gy), 0.0, 1.0));
    }
    if (uPeak == 1) {
        // Relative to the local brightness (Weber contrast), so peaking fires alike on dark and
        // bright specimens; scaled to match the absolute thresholds at mid-grey.
        float gn = g * 0.55 / (luma(blur) + 0.05);
        float k = smoothstep(0.28, 0.55, gn);
        col = mix(col, vec3(0.37, 0.92, 0.83), k * 0.85);
    }
    if (uZebra == 1 && max(c.r, max(c.g, c.b)) > 0.98) {
        // Diagonal stripes in screen space, so they never look like specimen texture.
        float stripe = step(0.5, fract((gl_FragCoord.x + gl_FragCoord.y) / 16.0));
        col = mix(col, vec3(stripe), 0.8);
    }
    outColor = vec4(col, 1.0);
}
"""
    }
}
