package com.streambridge

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import org.webrtc.SurfaceViewRenderer

class StreamActivity : FragmentActivity() {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        private const val SIGNALING_PORT = 8080
    }

    private lateinit var surfaceView: SurfaceViewRenderer
    private lateinit var tvHudStatus: TextView

    // Nullable so onDestroy is safe if onCreate exits early (missing EXTRA_HOST_IP)
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

        val mgr = WebRTCManager(this, surfaceView) { status ->
            runOnUiThread { tvHudStatus.text = status }
        }
        webRtcManager = mgr

        signalingClient = SignalingClient(signalingUrl, mgr)
        inputHandler = InputHandler(mgr)

        mgr.start()
        signalingClient?.connect()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return inputHandler?.onKeyDown(keyCode) == true || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return inputHandler?.onKeyUp(keyCode) == true || super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        webRtcManager?.dispose()
    }
}
