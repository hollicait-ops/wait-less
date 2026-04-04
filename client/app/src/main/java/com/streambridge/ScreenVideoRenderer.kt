package com.streambridge

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.AttributeSet
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * WebRTC VideoSink that renders video frames using a BT.601 limited-range
 * YUV→RGB shader. Handles both I420 buffers (software decode fallback) and
 * TextureBuffers (hardware decode via DefaultVideoDecoderFactory) — TextureBuffer
 * frames are converted to I420 via toI420() which handles its own EGL context.
 *
 * Tunable colour controls: [gGain] (green channel multiplier, default 0.97) and
 * [blackLift] (shadow offset, default 0.0 — negative values crush shadows darker).
 */
class ScreenVideoRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs), VideoSink {

    private val frameLock = Any()
    private var pendingFrame: VideoFrame? = null

    // Tunable colour controls — set from any thread, read on the GL thread.
    @Volatile var gGain: Float = 0.97f
    @Volatile var blackLift: Float = 0.0f

    init {
        setEGLContextClientVersion(2)
        setRenderer(I420Renderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onFrame(frame: VideoFrame) {
        frame.retain()
        synchronized(frameLock) {
            pendingFrame?.release()
            pendingFrame = frame
        }
        requestRender()
    }

    private inner class I420Renderer : GLSurfaceView.Renderer {

        // BT.601 studio swing (limited range) — matches libyuv ARGBToI420 output.
        // u_ggain and u_lift are tunable at runtime via ScreenVideoRenderer.gGain / blackLift.
        private val fragSrc = """
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

        private val vertSrc = """
            attribute vec2 a_pos;
            attribute vec2 a_tc;
            uniform vec2 u_scale;
            varying vec2 v_tc;
            void main() { gl_Position = vec4(a_pos * u_scale, 0.0, 1.0); v_tc = a_tc; }
        """.trimIndent()

        // Full-screen quad, triangle-strip order: BL, BR, TL, TR.
        // Texture Y is flipped (1→0 top-to-bottom) because glTexImage2D treats
        // row 0 of the buffer as the bottom of the texture.
        private val quadPos: FloatBuffer = buf(floatArrayOf(-1f, -1f,  1f, -1f,  -1f, 1f,  1f, 1f))
        private val quadTc:  FloatBuffer = buf(floatArrayOf( 0f,  1f,  1f,  1f,   0f, 0f,  1f, 0f))

        private var program = 0
        private val texIds = IntArray(3)
        private var yLoc = 0; private var uLoc = 0; private var vLoc = 0
        private var posLoc = 0; private var tcLoc = 0; private var scaleLoc = 0
        private var ggainLoc = 0; private var liftLoc = 0
        private var viewW = 0; private var viewH = 0

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            program = linkProgram(compileShader(GLES20.GL_VERTEX_SHADER, vertSrc),
                                  compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc))
            GLES20.glGenTextures(3, texIds, 0)
            // Y plane (texIds[0]): GL_NEAREST — full-res luma, 1:1 with display pixels.
            // U/V planes (texIds[1,2]): GL_LINEAR — half-res chroma (960x540 for 1080p).
            // Nearest-neighbour on chroma causes hard colour fringing every 2 pixels;
            // bilinear interpolation smooths the 2:1 chroma-to-luma step cleanly.
            for ((i, id) in texIds.withIndex()) {
                val filter = if (i == 0) GLES20.GL_NEAREST else GLES20.GL_LINEAR
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            yLoc   = GLES20.glGetUniformLocation(program, "y_tex")
            uLoc   = GLES20.glGetUniformLocation(program, "u_tex")
            vLoc   = GLES20.glGetUniformLocation(program, "v_tex")
            posLoc   = GLES20.glGetAttribLocation(program, "a_pos")
            tcLoc    = GLES20.glGetAttribLocation(program, "a_tc")
            scaleLoc = GLES20.glGetUniformLocation(program, "u_scale")
            ggainLoc = GLES20.glGetUniformLocation(program, "u_ggain")
            liftLoc  = GLES20.glGetUniformLocation(program, "u_lift")
        }

        override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            viewW = w; viewH = h
        }

        override fun onDrawFrame(unused: GL10?) {
            if (viewW == 0 || viewH == 0) return
            val frame = synchronized(frameLock) { pendingFrame.also { pendingFrame = null } }
                ?: return

            try {
                val i420 = frame.buffer.toI420() ?: run { frame.release(); return }
                try {
                    upload(texIds[0], i420.width,     i420.height,     i420.dataY, i420.strideY)
                    upload(texIds[1], i420.width / 2, i420.height / 2, i420.dataU, i420.strideU)
                    upload(texIds[2], i420.width / 2, i420.height / 2, i420.dataV, i420.strideV)

                    // Scale the quad to preserve the frame's aspect ratio.
                    // If the frame is wider than the viewport, fit to width and
                    // letterbox top/bottom; otherwise fit to height and pillarbox.
                    val frameAspect = i420.width.toFloat() / i420.height
                    val viewAspect  = viewW.toFloat() / viewH
                    val sx: Float; val sy: Float
                    if (frameAspect > viewAspect) { sx = 1f; sy = viewAspect / frameAspect }
                    else                          { sx = frameAspect / viewAspect; sy = 1f }

                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glUseProgram(program)
                    GLES20.glUniform2f(scaleLoc, sx, sy)
                    GLES20.glUniform1f(ggainLoc, gGain)
                    GLES20.glUniform1f(liftLoc, blackLift)

                    bindTex(0, texIds[0], yLoc)
                    bindTex(1, texIds[1], uLoc)
                    bindTex(2, texIds[2], vLoc)

                    GLES20.glEnableVertexAttribArray(posLoc)
                    GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, quadPos)
                    GLES20.glEnableVertexAttribArray(tcLoc)
                    GLES20.glVertexAttribPointer(tcLoc,  2, GLES20.GL_FLOAT, false, 0, quadTc)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                } finally {
                    i420.release()
                }
            } finally {
                frame.release()
            }
        }

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

        private fun bindTex(unit: Int, texId: Int, loc: Int) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId)
            GLES20.glUniform1i(loc, unit)
        }

        private fun compileShader(type: Int, src: String): Int {
            val id = GLES20.glCreateShader(type)
            GLES20.glShaderSource(id, src)
            GLES20.glCompileShader(id)
            val status = IntArray(1)
            GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) Log.e("ScreenVideoRenderer", "Shader compile error: ${GLES20.glGetShaderInfoLog(id)}")
            return id
        }

        private fun linkProgram(vs: Int, fs: Int): Int {
            val prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, vs)
            GLES20.glAttachShader(prog, fs)
            GLES20.glLinkProgram(prog)
            return prog
        }

        private fun buf(data: FloatArray): FloatBuffer =
            ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .also { it.put(data); it.position(0) }
    }
}
