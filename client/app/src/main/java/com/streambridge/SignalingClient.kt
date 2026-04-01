package com.streambridge

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * WebSocket signaling client. Connects to the Electron host signaling server,
 * parses incoming SDP/ICE messages, and dispatches them to WebRTCManager.
 */
class SignalingClient(
    private val url: String,
    private val webRtcManager: WebRTCManager,
) {
    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Connected — host will send an offer once capture starts
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // TODO (SB-8): surface error to StreamActivity for reconnect UI
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // TODO (SB-8): handle graceful close
            }
        })
    }

    fun send(json: JSONObject) {
        ws?.send(json.toString())
    }

    fun disconnect() {
        ws?.close(1000, "Activity destroyed")
        // Do not shut down client.dispatcher.executorService — OkHttp manages its own
        // thread pool lifecycle and shutting it down would break any reconnect attempt.
    }

    private fun handleMessage(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "offer" -> webRtcManager.onRemoteOffer(msg)
            "answer" -> webRtcManager.onRemoteAnswer(msg)
            "ice-candidate" -> webRtcManager.onRemoteIceCandidate(msg)
        }
    }
}
