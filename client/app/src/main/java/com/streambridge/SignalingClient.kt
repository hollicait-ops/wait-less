package com.streambridge

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

// ---------------------------------------------------------------------------
// Message parsing
// ---------------------------------------------------------------------------

sealed class SignalingMessage {
    data class StreamInfo(val videoPort: Int, val inputPort: Int) : SignalingMessage()
}

fun parseSignalingMessage(text: String): SignalingMessage? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    return when (json.optString("type")) {
        "stream-info" -> SignalingMessage.StreamInfo(
            videoPort = json.optInt("videoPort", 9000),
            inputPort  = json.optInt("inputPort",  9001),
        )
        else -> null
    }
}

// ---------------------------------------------------------------------------
// SignalingClient
// ---------------------------------------------------------------------------

/**
 * WebSocket signaling client. Connects to the host, receives stream-info,
 * and reports connection state via [Listener].
 */
class SignalingClient(
    private val url: String,
    private val listener: Listener,
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    interface Listener {
        fun onConnected()
        fun onStreamInfo(videoPort: Int, inputPort: Int)
        fun onDisconnected(reason: String)
        fun onError(message: String)
    }

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(url).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Connected to $url")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: ${text.take(120)}")
                when (val msg = parseSignalingMessage(text)) {
                    is SignalingMessage.StreamInfo -> listener.onStreamInfo(msg.videoPort, msg.inputPort)
                    null -> Log.w(TAG, "Ignored unrecognised message: ${text.take(80)}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                listener.onError(t.message ?: "Unknown error")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Disconnected: code=$code reason=${reason.ifEmpty { "none" }}")
                listener.onDisconnected(reason.ifEmpty { "Connection closed" })
            }
        })
    }

    fun send(text: String) {
        ws?.send(text)
    }

    fun disconnect() {
        ws?.close(1000, "Activity destroyed")
    }
}
