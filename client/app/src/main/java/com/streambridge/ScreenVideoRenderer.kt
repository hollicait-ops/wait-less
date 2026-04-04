package com.streambridge

import android.content.Context
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import org.webrtc.EglBase
import org.webrtc.EglBase14
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebRTC VideoSink that renders video frames using manual EGL14 context management.
 * Supports both hardware decode (OES textures via DefaultVideoDecoderFactory) and
 * software decode fallback (I420 buffers via SoftwareVideoDecoderFactory).
 *
 * The EGL context is created as a child of the decoder's EglBase, allowing direct
 * access to OES textures without GPU-to-CPU readback.
 *
 * Tunable colour controls: [gGain] (green channel multiplier, default 0.97) and
 * [blackLift] (shadow offset, default 0.0 — negative values crush shadows darker).
 */
class ScreenVideoRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), VideoSink, SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ScreenVideoRenderer"
    }

    private val frameLock = Any()
    private var pendingFrame: VideoFrame? = null

    @Volatile var gGain: Float = 0.97f
    @Volatile var blackLift: Float = 0.0f
    private val drawPending = AtomicBoolean(false)

    @Volatile private var sharedContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var glThread: HandlerThread? = null
    @Volatile private var glHandler: Handler? = null
    // Surface stored here if surfaceCreated fires before init() — init() drains it.
    @Volatile private var pendingSurface: android.view.Surface? = null

    // ---- EGL14 state (touched only on glThread) ----
    private var eglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- GL state (touched only on glThread) ----
    private var i420Program = 0
    private var oesProgram = 0

    // I420 program locations
    private var i420_yLoc = 0; private var i420_uLoc = 0; private var i420_vLoc = 0
    private var i420_posLoc = 0; private var i420_tcLoc = 0
    private var i420_scaleLoc = 0; private var i420_texMatLoc = 0
    private var i420_ggainLoc = 0; private var i420_liftLoc = 0

    // OES program locations
    private var oes_texLoc = 0
    private var oes_posLoc = 0; private var oes_tcLoc = 0
    private var oes_scaleLoc = 0; private var oes_texMatLoc = 0
    private var oes_ggainLoc = 0; private var oes_liftLoc = 0

    private val yuvTexIds = IntArray(3)
    private var viewW = 0; private var viewH = 0
    private var glInitialized = false

    // Quad geometry — standard (non-flipped) texture coordinates. Orientation is
    // handled by u_texMatrix: Y-flip for I420, TextureBuffer transform for OES.
    private val quadPos: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTc: FloatBuffer = buf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    // Y-flip matrix for I420 path: maps (u,v) → (u, 1-v) because glTexImage2D
    // treats row 0 as the bottom of the texture.
    private val i420TexMatrix = floatArrayOf(
        1f,  0f, 0f, 0f,
        0f, -1f, 0f, 0f,
        0f,  0f, 1f, 0f,
        0f,  1f, 0f, 1f,
    )

    // ---- Shaders ----

    private val vertSrc = """
        attribute vec2 a_pos;
        attribute vec2 a_tc;
        uniform vec2 u_scale;
        uniform mat4 u_texMatrix;
        varying vec2 v_tc;
        void main() {
            gl_Position = vec4(a_pos * u_scale, 0.0, 1.0);
            v_tc = (u_texMatrix * vec4(a_tc, 0.0, 1.0)).xy;
        }
    """.trimIndent()

    // BT.601 studio swing (limited range) — matches libyuv ARGBToI420 output.
    private val i420FragSrc = """
        precision highp float;
        uniform sampler2D y_tex;
        uniform sampler2D u_tex;
        uniform sampler2D v_tex;
        uniform float u_ggain;
        uniform float u_lift;
        varying vec2 v_tc;
        void main() {
            float y  = texture2D(y_tex, v_tc).r - 0.0627;
            float cb = texture2D(u_tex, v_tc).r - 0.5020;
            float cr = texture2D(v_tex, v_tc).r - 0.5020;
            float r = clamp(1.1644*y + 1.5960*cr              - u_lift, 0.0, 1.0);
            float g = clamp((1.1644*y - 0.3918*cb - 0.8130*cr) * u_ggain - u_lift, 0.0, 1.0);
            float b = clamp(1.1644*y + 2.0172*cb              - u_lift, 0.0, 1.0);
            gl_FragColor = vec4(r, g, b, 1.0);
        }
    """.trimIndent()

    // RGB-space correction for hardware-decoded OES textures. The GPU has already
    // done YUV→RGB, so we only apply G-gain and black lift.
    private val oesFragSrc = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES u_tex;
        uniform float u_ggain;
        uniform float u_lift;
        varying vec2 v_tc;
        void main() {
            vec4 c = texture2D(u_tex, v_tc);
            float r = clamp(c.r - u_lift, 0.0, 1.0);
            float g = clamp(c.g * u_ggain - u_lift, 0.0, 1.0);
            float b = clamp(c.b - u_lift, 0.0, 1.0);
            gl_FragColor = vec4(r, g, b, 1.0);
        }
    """.trimIndent()

    // ---- Public API ----

    fun init(eglContext: EglBase.Context) {
        val raw = (eglContext as? EglBase14.Context)?.rawContext
        if (raw == null) {
            Log.e(TAG, "EglBase.Context is not EglBase14 — hardware decode requires EGL14; renderer will not initialise")
            return
        }
        sharedContext = raw
        holder.addCallback(this)
        glThread = HandlerThread("ScreenVideoRenderer").also { it.start() }
        glHandler = Handler(glThread!!.looper).also { handler ->
            // If surfaceCreated already fired before init() was called, drain it now.
            pendingSurface?.let { surface ->
                pendingSurface = null
                handler.post { initEgl(surface) }
            }
        }
    }

    override fun onFrame(frame: VideoFrame) {
        frame.retain()
        synchronized(frameLock) {
            pendingFrame?.release()
            pendingFrame = frame
        }
        // Only post if no draw is already queued — avoids handler queue buildup
        // when frames arrive faster than we can draw them.
        if (drawPending.compareAndSet(false, true)) {
            glHandler?.post(::drawFrame)
        }
    }

    fun release() {
        val thread = glThread ?: return
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        handler.post {
            releaseEgl()
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
        glThread = null
        glHandler = null
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        val handler = glHandler
        if (handler != null) {
            handler.post { initEgl(holder.surface) }
        } else {
            // init() hasn't been called yet — stash the surface; init() will drain it.
            pendingSurface = holder.surface
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        glHandler?.post {
            viewW = w; viewH = h
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                GLES20.glViewport(0, 0, w, h)
            }
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pendingSurface = null
        val handler = glHandler ?: return
        val latch = CountDownLatch(1)
        handler.post {
            releaseEgl()
            latch.countDown()
        }
        latch.await()
    }

    // ---- EGL setup/teardown (glThread only) ----

    private fun initEgl(surface: android.view.Surface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val eglConfig = configs[0] ?: run {
            Log.e(TAG, "eglChooseConfig returned no configs — EGL init failed")
            return
        }

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, sharedContext, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        // Don't block on vsync — present immediately. The display compositor handles
        // actual vsync; blocking here just adds a frame of buffering latency.
        EGL14.eglSwapInterval(eglDisplay, 0)

        initGl()
    }

    private fun releaseEgl() {
        glInitialized = false
        synchronized(frameLock) { pendingFrame?.release(); pendingFrame = null }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
    }

    // ---- GL setup (glThread only, called from initEgl) ----

    private fun initGl() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)

        // I420 program
        i420Program = linkProgram(vs, compileShader(GLES20.GL_FRAGMENT_SHADER, i420FragSrc))
        i420_yLoc      = GLES20.glGetUniformLocation(i420Program, "y_tex")
        i420_uLoc      = GLES20.glGetUniformLocation(i420Program, "u_tex")
        i420_vLoc      = GLES20.glGetUniformLocation(i420Program, "v_tex")
        i420_posLoc    = GLES20.glGetAttribLocation(i420Program, "a_pos")
        i420_tcLoc     = GLES20.glGetAttribLocation(i420Program, "a_tc")
        i420_scaleLoc  = GLES20.glGetUniformLocation(i420Program, "u_scale")
        i420_texMatLoc = GLES20.glGetUniformLocation(i420Program, "u_texMatrix")
        i420_ggainLoc  = GLES20.glGetUniformLocation(i420Program, "u_ggain")
        i420_liftLoc   = GLES20.glGetUniformLocation(i420Program, "u_lift")

        // OES program
        oesProgram = linkProgram(vs, compileShader(GLES20.GL_FRAGMENT_SHADER, oesFragSrc))
        oes_texLoc    = GLES20.glGetUniformLocation(oesProgram, "u_tex")
        oes_posLoc    = GLES20.glGetAttribLocation(oesProgram, "a_pos")
        oes_tcLoc     = GLES20.glGetAttribLocation(oesProgram, "a_tc")
        oes_scaleLoc  = GLES20.glGetUniformLocation(oesProgram, "u_scale")
        oes_texMatLoc = GLES20.glGetUniformLocation(oesProgram, "u_texMatrix")
        oes_ggainLoc  = GLES20.glGetUniformLocation(oesProgram, "u_ggain")
        oes_liftLoc   = GLES20.glGetUniformLocation(oesProgram, "u_lift")

        // YUV textures for I420 path
        GLES20.glGenTextures(3, yuvTexIds, 0)
        // Y plane: GL_NEAREST (full-res luma, 1:1 with display)
        // U/V planes: GL_LINEAR (half-res chroma, interpolate the 2:1 step)
        for ((i, id) in yuvTexIds.withIndex()) {
            val filter = if (i == 0) GLES20.GL_NEAREST else GLES20.GL_LINEAR
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        glInitialized = true
    }

    // ---- Drawing (glThread only) ----

    private fun drawFrame() {
        drawPending.set(false)
        if (!glInitialized || viewW == 0 || viewH == 0) return
        val frame = synchronized(frameLock) { pendingFrame.also { pendingFrame = null } } ?: return

        try {
            val buffer = frame.buffer
            if (buffer is VideoFrame.TextureBuffer && buffer.type == VideoFrame.TextureBuffer.Type.OES) {
                drawOes(buffer, frame.rotatedWidth, frame.rotatedHeight)
            } else {
                val i420 = buffer.toI420() ?: return
                try {
                    drawI420(i420)
                } finally {
                    i420.release()
                }
            }
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        } finally {
            frame.release()
        }
    }

    private fun drawOes(buffer: VideoFrame.TextureBuffer, frameW: Int, frameH: Int) {
        val (sx, sy) = aspectScale(frameW, frameH)
        val texMatrix = matrixTo4x4(buffer.transformMatrix)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(oesProgram)
        GLES20.glUniform2f(oes_scaleLoc, sx, sy)
        GLES20.glUniformMatrix4fv(oes_texMatLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1f(oes_ggainLoc, gGain)
        GLES20.glUniform1f(oes_liftLoc, blackLift)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, buffer.textureId)
        GLES20.glUniform1i(oes_texLoc, 0)

        drawQuad(oes_posLoc, oes_tcLoc)
    }

    private fun drawI420(i420: VideoFrame.I420Buffer) {
        upload(yuvTexIds[0], i420.width, i420.height, i420.dataY, i420.strideY)
        upload(yuvTexIds[1], i420.width / 2, i420.height / 2, i420.dataU, i420.strideU)
        upload(yuvTexIds[2], i420.width / 2, i420.height / 2, i420.dataV, i420.strideV)

        val (sx, sy) = aspectScale(i420.width, i420.height)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(i420Program)
        GLES20.glUniform2f(i420_scaleLoc, sx, sy)
        GLES20.glUniformMatrix4fv(i420_texMatLoc, 1, false, i420TexMatrix, 0)
        GLES20.glUniform1f(i420_ggainLoc, gGain)
        GLES20.glUniform1f(i420_liftLoc, blackLift)

        bindTex2D(0, yuvTexIds[0], i420_yLoc)
        bindTex2D(1, yuvTexIds[1], i420_uLoc)
        bindTex2D(2, yuvTexIds[2], i420_vLoc)

        drawQuad(i420_posLoc, i420_tcLoc)
    }

    private fun drawQuad(posLoc: Int, tcLoc: Int) {
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(tcLoc)
        GLES20.glVertexAttribPointer(tcLoc, 2, GLES20.GL_FLOAT, false, 0, quadTc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun aspectScale(frameW: Int, frameH: Int): Pair<Float, Float> {
        val frameAspect = frameW.toFloat() / frameH
        val viewAspect = viewW.toFloat() / viewH
        return if (frameAspect > viewAspect) Pair(1f, viewAspect / frameAspect)
        else Pair(frameAspect / viewAspect, 1f)
    }

    // ---- Helpers ----

    private fun upload(texId: Int, w: Int, h: Int, data: ByteBuffer, stride: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        val compact = if (stride == w) {
            data.also { it.position(0) }
        } else {
            val out = ByteBuffer.allocateDirect(w * h)
            val row = ByteArray(w)
            for (r in 0 until h) {
                data.position(r * stride)
                data.get(row)
                out.put(row)
            }
            out.also { it.position(0) }
        }
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
            w, h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, compact)
    }

    private fun bindTex2D(unit: Int, texId: Int, loc: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
        GLES20.glUniform1i(loc, unit)
    }

    private fun matrixTo4x4(m: Matrix): FloatArray {
        val v = FloatArray(9)
        m.getValues(v)
        return floatArrayOf(
            v[0], v[3], 0f, v[6],
            v[1], v[4], 0f, v[7],
            0f,   0f,   1f, 0f,
            v[2], v[5], 0f, v[8],
        )
    }

    private fun compileShader(type: Int, src: String): Int {
        val id = GLES20.glCreateShader(type)
        GLES20.glShaderSource(id, src)
        GLES20.glCompileShader(id)
        val status = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(id)}")
        return id
    }

    private fun linkProgram(vs: Int, fs: Int): Int {
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val status = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "Program link error: ${GLES20.glGetProgramInfoLog(prog)}")
        return prog
    }

    private fun buf(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { it.put(data); it.position(0) }
}
