package com.streambridge

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.webrtc.EglBase

class StreamActivity : FragmentActivity() {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        private const val SIGNALING_PORT = 8080
    }

    private lateinit var surfaceView: ScreenVideoRenderer
    private lateinit var tvHudStatus: TextView

    // Nullable so onDestroy is safe if onCreate exits early (missing EXTRA_HOST_IP)
    private var eglBase: EglBase? = null
    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRTCManager? = null
    private var inputHandler: InputHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on; go edge-to-edge
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)

        surfaceView = findViewById(R.id.surface_view)
        tvHudStatus = findViewById(R.id.tv_hud_status)

        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: run { finish(); return }
        val signalingUrl = "ws://$hostIp:$SIGNALING_PORT"

        val base = EglBase.create()
        eglBase = base
        surfaceView.init(base.eglBaseContext)

        val mgr = WebRTCManager(this, surfaceView, base.eglBaseContext) { status ->
            runOnUiThread { tvHudStatus.text = status }
        }
        webRtcManager = mgr

        signalingClient = SignalingClient(
            url = signalingUrl,
            webRtcManager = mgr,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    runOnUiThread { tvHudStatus.text = "Connected — waiting for stream..." }
                }
                override fun onDisconnected(reason: String) {
                    runOnUiThread { tvHudStatus.text = "Disconnected: $reason" }
                }
                override fun onError(message: String) {
                    runOnUiThread { tvHudStatus.text = "Signaling error: $message" }
                }
            },
        )
        inputHandler = InputHandler(mgr)

        mgr.start()

        val sc = signalingClient
        if (sc != null) mgr.onSendSignaling = { json -> sc.send(json) }

        signalingClient?.connect()
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
        webRtcManager?.dispose()
        surfaceView.release()
        eglBase?.release()
        eglBase = null
    }
}
