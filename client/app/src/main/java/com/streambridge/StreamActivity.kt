package com.streambridge

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

class StreamActivity : FragmentActivity() {

    companion object {
        const val EXTRA_HOST_IP = "host_ip"
        private const val SIGNALING_PORT = 8080
    }

    private lateinit var surfaceView: ScreenVideoRenderer
    private lateinit var tvHudStatus: TextView

    private var signalingClient: SignalingClient? = null
    private var udpReceiver: UdpVideoReceiver? = null
    private var inputHandler: InputHandler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_stream)

        surfaceView = findViewById(R.id.surface_view)
        tvHudStatus = findViewById(R.id.tv_hud_status)

        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: run { finish(); return }
        val signalingUrl = "ws://$hostIp:$SIGNALING_PORT"

        surfaceView.init()

        signalingClient = SignalingClient(
            url = signalingUrl,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    runOnUiThread { tvHudStatus.text = "Connected — waiting for stream..." }
                }

                override fun onStreamInfo(videoPort: Int, inputPort: Int) {
                    // awaitInputSurface() blocks until GL is ready — run off the main thread
                    Thread {
                        val surface = surfaceView.awaitInputSurface()
                        val receiver = UdpVideoReceiver(
                            outputSurface = surface,
                            hostIp = hostIp,
                            videoPort = videoPort,
                            inputPort = inputPort,
                            onStatus = { msg -> runOnUiThread { tvHudStatus.text = msg } },
                        )
                        udpReceiver = receiver
                        inputHandler = InputHandler(receiver)
                        receiver.start()
                        runOnUiThread { tvHudStatus.text = "Streaming" }
                    }.also { it.isDaemon = true }.start()
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler?.onKeyDown(keyCode) == true || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return inputHandler?.onKeyUp(keyCode) == true || super.onKeyUp(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient?.disconnect()
        udpReceiver?.stop()
        surfaceView.release()
    }
}
