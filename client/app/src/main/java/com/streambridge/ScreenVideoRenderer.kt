package com.streambridge

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SurfaceView that renders hardware-decoded video via an OES texture.
 *
 * MediaCodec decodes H.264 directly into a [SurfaceTexture] (created inside
 * this renderer on the GL thread). Callers obtain the [Surface] wrapping that
 * SurfaceTexture via [awaitInputSurface] and hand it to MediaCodec as the
 * decode target. Each decoded frame triggers a GL draw automatically via
 * [SurfaceTexture.OnFrameAvailableListener].
 *
 * Tunable colour controls: [gGain] (green channel multiplier, default 0.97)
 * and [blackLift] (shadow offset, default 0.0).
 */
class ScreenVideoRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "ScreenVideoRenderer"
    }

    @Volatile var gGain: Float = 0.97f
    @Volatile var blackLift: Float = 0.0f
    private val drawPending = AtomicBoolean(false)

    private var glThread: HandlerThread? = null
    @Volatile private var glHandler: Handler? = null
    @Volatile private var pendingSurface: android.view.Surface? = null

    // ---- EGL14 state (glThread only) ----
    private var eglDisplay: android.opengl.EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: android.opengl.EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: android.opengl.EGLSurface = EGL14.EGL_NO_SURFACE

    // ---- GL/SurfaceTexture state (glThread only) ----
    private var oesProgram = 0
    private var oes_texLoc = 0
    private var oes_posLoc = 0; private var oes_tcLoc = 0
    private var oes_scaleLoc = 0; private var oes_texMatLoc = 0
    private var oes_ggainLoc = 0; private var oes_liftLoc = 0

    private var oesTexId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var stAttached = false

    // Latch used by awaitInputSurface() to hand the Surface to MediaCodec
    private val inputSurfaceLatch = CountDownLatch(1)
    private var inputSurface: Surface? = null

    private var viewW = 0; private var viewH = 0
    private var glInitialized = false

    private val quadPos: FloatBuffer = buf(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val quadTc: FloatBuffer = buf(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

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

    private val oesFragSrc = """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES u_tex;
        uniform float u_ggain;
        uniform float u_lift;
        varying vec2 v_tc;
        void main() {
            vec4 c = texture2D(u_tex, v_tc);
            c.rgb = max(c.rgb + u_lift, 0.0);
            c.g *= u_ggain;
            gl_FragColor = c;
        }
    """.trimIndent()

    // ---- Public API ----

    /**
     * Initialise the renderer. Must be called before the view is attached to a window.
     */
    fun init() {
        // Create the SurfaceTexture in detached mode — no EGL context needed.
        // This lets MediaCodec output to its Surface without driver conflicts
        // on devices (e.g. Fire Stick) where the codec can't write to a
        // Surface created inside an active EGL context.
        surfaceTexture = SurfaceTexture(false).also { st ->
            st.setDefaultBufferSize(1920, 1080)
            st.setOnFrameAvailableListener {
                if (drawPending.compareAndSet(false, true)) {
                    glHandler?.post(::drawFrame)
                }
            }
        }
        inputSurface = Surface(surfaceTexture)
        inputSurfaceLatch.countDown()

        holder.addCallback(this)
        glThread = HandlerThread("ScreenVideoRenderer").also { it.start() }
        glHandler = Handler(glThread!!.looper).also { handler ->
            pendingSurface?.let { surface ->
                pendingSurface = null
                handler.post { initEgl(surface) }
            }
        }
    }

    /**
     * Direct-surface mode: register the surface lifecycle callback and expose the
     * SurfaceView's own display surface via [awaitInputSurface]. MediaCodec decodes
     * directly to the SurfaceView — no GL pipeline. Safe to call instead of [init].
     */
    fun initDirect() {
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                inputSurface = h.surface
                inputSurfaceLatch.countDown()
                Log.i(TAG, "surfaceCreated — surface ready for MediaCodec")
            }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, height: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                inputSurface = null
            }
        })
    }

    /**
     * Blocks until the input surface is ready, then returns it.
     * Works for both GL mode ([init]) and direct mode ([initDirect]).
     */
    fun awaitInputSurface(): Surface {
        inputSurfaceLatch.await()
        return inputSurface!!
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
        surfaceTexture?.release()
        surfaceTexture = null
        inputSurface?.release()
        inputSurface = null
    }

    // ---- SurfaceHolder.Callback ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        val handler = glHandler
        if (handler != null) {
            handler.post { initEgl(holder.surface) }
        } else {
            pendingSurface = holder.surface
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        glHandler?.post {
            viewW = w; viewH = h
            if (eglContext != EGL14.EGL_NO_CONTEXT) GLES20.glViewport(0, 0, w, h)
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
        // Standalone context — MediaCodec decodes directly to our SurfaceTexture,
        // no shared EGL context with the decoder needed.
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, surfaceAttribs, 0)

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        EGL14.eglSwapInterval(eglDisplay, 0)

        initGl()
    }

    private fun releaseEgl() {
        glInitialized = false
        if (stAttached) {
            surfaceTexture?.detachFromGLContext()
            stAttached = false
        }
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

    // ---- GL setup (glThread only) ----

    private fun initGl() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        oesProgram = linkProgram(vs, compileShader(GLES20.GL_FRAGMENT_SHADER, oesFragSrc))
        oes_texLoc    = GLES20.glGetUniformLocation(oesProgram, "u_tex")
        oes_posLoc    = GLES20.glGetAttribLocation(oesProgram, "a_pos")
        oes_tcLoc     = GLES20.glGetAttribLocation(oesProgram, "a_tc")
        oes_scaleLoc  = GLES20.glGetUniformLocation(oesProgram, "u_scale")
        oes_texMatLoc = GLES20.glGetUniformLocation(oesProgram, "u_texMatrix")
        oes_ggainLoc  = GLES20.glGetUniformLocation(oesProgram, "u_ggain")
        oes_liftLoc   = GLES20.glGetUniformLocation(oesProgram, "u_lift")

        // Create the OES texture that MediaCodec will decode into via SurfaceTexture
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        oesTexId = texIds[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // Attach the detached SurfaceTexture to the OES texture now that GL is ready
        surfaceTexture!!.attachToGLContext(oesTexId)
        stAttached = true

        GLES20.glClearColor(0f, 0f, 0f, 1f)
        glInitialized = true
        Log.i(TAG, "GL initialised — SurfaceTexture attached to OES texture $oesTexId")
    }

    // ---- Drawing (glThread only) ----

    private fun drawFrame() {
        drawPending.set(false)
        if (!glInitialized || viewW == 0 || viewH == 0) return

        val st = surfaceTexture ?: return
        st.updateTexImage()

        val texMatrix = FloatArray(16)
        st.getTransformMatrix(texMatrix)

        // FFmpeg encodes at a fixed 1920x1080; use that for aspect ratio calculation.
        // SurfaceTexture does not expose decoded frame dimensions directly.
        val frameW = 1920
        val frameH = 1080
        val (sx, sy) = aspectScale(frameW, frameH)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(oesProgram)
        GLES20.glUniform2f(oes_scaleLoc, sx, sy)
        GLES20.glUniformMatrix4fv(oes_texMatLoc, 1, false, texMatrix, 0)
        GLES20.glUniform1f(oes_ggainLoc, gGain)
        GLES20.glUniform1f(oes_liftLoc, blackLift)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId)
        GLES20.glUniform1i(oes_texLoc, 0)

        GLES20.glEnableVertexAttribArray(oes_posLoc)
        GLES20.glVertexAttribPointer(oes_posLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos)
        GLES20.glEnableVertexAttribArray(oes_tcLoc)
        GLES20.glVertexAttribPointer(oes_tcLoc, 2, GLES20.GL_FLOAT, false, 0, quadTc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    private fun aspectScale(frameW: Int, frameH: Int): Pair<Float, Float> {
        if (viewW == 0 || viewH == 0) return Pair(1f, 1f)
        val frameAspect = frameW.toFloat() / frameH
        val viewAspect = viewW.toFloat() / viewH
        return if (frameAspect > viewAspect) Pair(1f, viewAspect / frameAspect)
        else Pair(frameAspect / viewAspect, 1f)
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
