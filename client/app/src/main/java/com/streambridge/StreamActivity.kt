package com.streambridge

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

class StreamActivity : FragmentActivity() {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        private const val SIGNALING_PORT = 8080
        private const val TAG = "StreamActivity"
    }

    private lateinit var surfaceView: ScreenVideoRenderer
    private lateinit var tvHudStatus: TextView

    private var signalingClient: SignalingClient? = null
    private var udpReceiver: UdpVideoReceiver? = null
    private var inputHandler: InputHandler? = null

    // Saved when onStreamInfo fires; used to (re)start the receiver on surfaceCreated.
    private var hostIp: String = ""
    private var pendingVideoPort: Int = -1
    private var pendingInputPort: Int = -1

    // True once client-ready has been sent for this session; prevents double-sends
    // when the surface is destroyed and recreated mid-stream.
    private var clientReadySent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)

        surfaceView = findViewById(R.id.surface_view)
        tvHudStatus = findViewById(R.id.tv_hud_status)

        hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: run { finish(); return }

        // Register surface lifecycle before connecting so we never miss surfaceCreated.
        // All three callbacks fire on the main thread.
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceCreated")
                if (pendingVideoPort >= 0) startReceiver(holder.surface)
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "surfaceDestroyed — stopping receiver")
                stopReceiver()
            }
        })

        signalingClient = SignalingClient(
            url = "ws://$hostIp:$SIGNALING_PORT",
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    runOnUiThread { tvHudStatus.text = "Connected — waiting for stream..." }
                }

                override fun onStreamInfo(videoPort: Int, inputPort: Int) {
                    Log.i(TAG, "onStreamInfo video=$videoPort input=$inputPort")
                    // Move to main thread so surface access and receiver lifecycle
                    // are all serialised with the SurfaceHolder callbacks above.
                    runOnUiThread {
                        pendingVideoPort = videoPort
                        pendingInputPort = inputPort
                        val surface = surfaceView.holder.surface
                        if (surface.isValid) startReceiver(surface)
                        // else surfaceCreated will fire shortly and call startReceiver
                    }
                }

                override fun onDisconnected(reason: String) {
                    runOnUiThread { tvHudStatus.text = "Disconnected: $reason" }
                }

                override fun onError(message: String) {
                    runOnUiThread { tvHudStatus.text = "Signaling error: $message" }
                }
            },
        )

        signalingClient?.connect()
    }

    // Must be called on the main thread (enforced by callers above).
    private fun startReceiver(surface: Surface) {
        stopReceiver()
        Log.i(TAG, "startReceiver surface=$surface")
        val receiver = UdpVideoReceiver(
            outputSurface = surface,
            hostIp = hostIp,
            videoPort = pendingVideoPort,
            inputPort = pendingInputPort,
            onStatus = { msg -> runOnUiThread { tvHudStatus.text = msg } },
        )
        udpReceiver = receiver
        inputHandler = InputHandler(receiver)
        receiver.start()

        if (!clientReadySent) {
            Log.i(TAG, "sending client-ready")
            signalingClient?.send("""{"type":"client-ready"}""")
            clientReadySent = true
        }
        tvHudStatus.text = "Streaming"
    }

    // Must be called on the main thread.
    private fun stopReceiver() {
        udpReceiver?.stop()
        udpReceiver = null
        inputHandler = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler?.onKeyDown(keyCode) == true || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler?.onKeyUp(keyCode) == true || super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        stopReceiver()
    }
}
