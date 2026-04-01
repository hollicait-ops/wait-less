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

    private lateinit var signalingClient: SignalingClient
    private lateinit var webRtcManager: WebRTCManager
    private lateinit var inputHandler: InputHandler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep screen on; go edge-to-edge
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)

        surfaceView = findViewById(R.id.surface_view)
        tvHudStatus = findViewById(R.id.tv_hud_status)

        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: run { finish(); return }
        val signalingUrl = "ws://$hostIp:$SIGNALING_PORT"

        webRtcManager = WebRTCManager(this, surfaceView) { status ->
            runOnUiThread { tvHudStatus.text = status }
        }

        signalingClient = SignalingClient(signalingUrl, webRtcManager)
        inputHandler = InputHandler(webRtcManager)

        webRtcManager.start()
        signalingClient.connect()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return inputHandler.onKeyDown(keyCode) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return inputHandler.onKeyUp(keyCode) || super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient.disconnect()
        webRtcManager.dispose()
    }
}
